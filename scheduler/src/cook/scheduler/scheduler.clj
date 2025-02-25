;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns cook.scheduler.scheduler
  (:require [chime :refer [chime-at chime-ch]]
            [clj-time.coerce :as tc]
            [clj-time.core :as time]
            [clojure.core.async :as async]
            [clojure.core.cache :as cache]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [cook.cached-queries :as cached-queries]
            [cook.cache :as ccache]
            [cook.caches :as caches]
            [cook.compute-cluster :as cc]
            [cook.config :as config]
            [cook.datomic :as datomic]
            [cook.group :as group]
            [cook.mesos.reason :as reason]
            [cook.mesos.task :as task]
            [cook.plugins.completion :as completion]
            [cook.plugins.definitions :as plugins]
            [cook.plugins.launch :as launch-plugin]
            [cook.pool :as pool]
            [cook.queries :as queries]
            [cook.quota :as quota]
            [cook.rate-limit :as ratelimit]
            [cook.scheduler.constraints :as constraints]
            [cook.scheduler.dru :as dru]
            [cook.scheduler.fenzo-utils :as fenzo]
            [cook.scheduler.offer :as offer]
            [cook.scheduler.share :as share]
            [cook.task]
            [cook.task-stats :as task-stats]
            [cook.tools :as tools]
            [cook.util :as util]
            [datomic.api :as d :refer [q]]
            [mesomatic.scheduler :as mesos]
            [metatransaction.core :refer [db]]
            [metrics.counters :as counters]
            [metrics.gauges :as gauges]
            [metrics.histograms :as histograms]
            [metrics.meters :as meters]
            [metrics.timers :as timers]
            [plumbing.core :as pc])
  (:import (com.netflix.fenzo
             TaskAssignmentResult TaskRequest TaskScheduler TaskScheduler$Builder VirtualMachineCurrentState
             VirtualMachineLease SchedulingResult VMAssignmentResult)
           (com.netflix.fenzo.functions Action1 Action2 Func1)
           (java.util LinkedList Date)))

(defn now
  ^Date []
  (tc/to-date (time/now)))


(timers/deftimer [cook-mesos scheduler handle-status-update-duration])
(timers/deftimer [cook-mesos scheduler handle-framework-message-duration])
(meters/defmeter [cook-mesos scheduler handle-framework-message-rate])

(timers/deftimer [cook-mesos scheduler generate-user-usage-map-duration])

(defn metric-title
  ([metric-name pool]
   ["cook-mesos" "scheduler" metric-name (str "pool-" pool)])
  ([metric-name pool compute-cluster]
   ["cook-mesos" "scheduler" metric-name (str "pool-" pool)
    (str "compute-cluster-" (cc/compute-cluster-name compute-cluster))]))

(defn completion-rate-meter
  [status pool]
  (let [metric-name (case status
                      :succeeded "tasks-succeeded"
                      :failed "tasks-failed"
                      :completed "tasks-completed")]
    (meters/meter (metric-title metric-name pool))))

(defn completion-mem-meter
  [status pool]
  (let [metric-name (case status
                      :succeeded "tasks-succeeded-mem"
                      :failed "tasks-failed-mem"
                      :completed "tasks-completed-mem")]
    (meters/meter (metric-title metric-name pool))))

(defn completion-cpus-meter
  [status pool]
  (let [metric-name (case status
                      :succeeded "tasks-succeeded-cpus"
                      :failed "tasks-failed-cpus"
                      :completed "tasks-completed-cpus")]
    (meters/meter (metric-title metric-name pool))))

(defn completion-run-times-histogram
  [status pool]
  (let [metric-name (case status
                      :succeeded "hist-task-succeed-times"
                      :failed "hist-task-fail-times"
                      :completed "hist-task-complete-times")]
    (histograms/histogram (metric-title metric-name pool))))

(defn completion-run-times-meter
  [status pool]
  (let [metric-name (case status
                      :succeeded "task-succeed-times"
                      :failed "task-fail-times"
                      :completed "task-complete-times")]
    (meters/meter (metric-title metric-name pool))))

(defn handle-throughput-metrics [job-resources run-time status pool]
  (let [completion-rate (completion-rate-meter status pool)
        completion-mem (completion-mem-meter status pool)
        completion-cpus (completion-cpus-meter status pool)
        completion-hist-run-times (completion-run-times-histogram status pool)
        completion-MA-run-times (completion-run-times-meter status pool)]
    (meters/mark! completion-rate)
    (meters/mark!
      completion-mem
      (:mem job-resources))
    (meters/mark!
      completion-cpus
      (:cpus job-resources))
    (histograms/update!
      completion-hist-run-times
      run-time)
    (meters/mark!
      completion-MA-run-times
      run-time)))

(defn interpret-task-status
  "Converts the status packet from Mesomatic into a more friendly data structure"
  [s]
  {:task-id (-> s :task-id :value)
   :reason (:reason s)
   :task-state (:state s)
   :progress (try
               (when (:data s)
                 (:percent (edn/read-string (String. (.toByteArray (:data s))))))
               (catch Exception e
                 (try (log/debug e (str "Error parsing mesos status data to edn."
                                        "Is it in the format we expect?"
                                        "String representation: "
                                        (String. (.toByteArray (:data s)))))
                      (catch Exception e
                        (log/debug e "Error reading a string from mesos status data. Is it in the format we expect?")))))})

(defn job->scalar-request
  "Takes job and makes the scalar-request for TaskRequestAdapter"
  [job]
  (reduce (fn [result resource]
            (let [{:keys [resource/amount resource/type]} resource]
              ; Task request shouldn't have a scalar request for GPUs because
              ; we are completely handling GPUs within GPU host constraint
              ; and fenzo cannot handle scheduling for multiple GPU models
              (if (and amount (not= type :resource.type/gpus))
                (assoc result (name type) amount)
                result)))
          {}
          (:job/resource job)))

(defn update-reason-metrics!
  "Updates histograms and counters for run time, cpu time, and memory time,
  where the histograms have the failure reason in the title"
  [db task-id mesos-reason instance-runtime {:keys [cpus mem]}]
  (let [reason (->> mesos-reason
                    (reason/mesos-reason->cook-reason-entity-id db task-id)
                    (d/entity db)
                    :reason/name
                    name)
        update-metrics! (fn update-metrics! [s v]
                          (histograms/update!
                            (histograms/histogram
                              ["cook-mesos" "scheduler" "hist-task-fail" reason s])
                            v)
                          (counters/inc!
                            (counters/counter
                              ["cook-mesos" "scheduler" "hist-task-fail" reason s "total"])
                            v))
        instance-runtime-seconds (/ instance-runtime 1000)
        mem-gb (/ mem 1024)]
    (update-metrics! "times" instance-runtime-seconds)
    (update-metrics! "cpu-times" (* instance-runtime-seconds cpus))
    (update-metrics! "mem-times" (* instance-runtime-seconds mem-gb))))

(defn write-status-to-datomic
  "Takes a status update from mesos."
  [conn pool-name->fenzo-state status]
  (log/info "Instance status is:" status)
  (timers/time!
    handle-status-update-duration
    (try (let [db (db conn)
               {:keys [task-id reason task-state progress]} (interpret-task-status status)
               _ (when-not task-id
                   (throw (ex-info "task-id is nil. Something unexpected has happened."
                                   {:status status
                                    :task-id task-id
                                    :reason reason
                                    :task-state task-state
                                    :progress progress})))
               [job instance prior-instance-status] (first (q '[:find ?j ?i ?status
                                                                :in $ ?task-id
                                                                :where
                                                                [?i :instance/task-id ?task-id]
                                                                [?i :instance/status ?s]
                                                                [?s :db/ident ?status]
                                                                [?j :job/instance ?i]]
                                                              db task-id))
               job-ent (d/entity db job)
               instance-ent (d/entity db instance)
               previous-reason (reason/instance-entity->reason-entity db instance-ent)
               instance-status (condp contains? task-state
                                 #{:task-staging} :instance.status/unknown
                                 #{:task-starting
                                   :task-running} :instance.status/running
                                 #{:task-finished} :instance.status/success
                                 #{:task-failed
                                   :task-killed
                                   :task-lost
                                   :task-error} :instance.status/failed)
               prior-job-state (:job/state (d/entity db job))
               ^Date current-time (now)
               ^Date start-time (or (:instance/start-time instance-ent) current-time)
               instance-runtime (- (.getTime current-time) ; Used for reporting
                                   (.getTime start-time))
               job-resources (tools/job-ent->resources job-ent)
               pool-name (cached-queries/job->pool-name job-ent)
               unassign-task-set (some-> pool-name pool-name->fenzo-state :unassign-task-set)]
           (when (#{:instance.status/success :instance.status/failed} instance-status)
             (if unassign-task-set
               (swap! unassign-task-set conj {:task-id task-id :hostname (:instance/hostname instance-ent)})
               (log/error "In" pool-name "pool, unable to unassign task" task-id "from"
                          (:instance/hostname instance-ent) "because fenzo state or unassign-task-set is nil:" (keys pool-name->fenzo-state))))
           (when (= instance-status :instance.status/success)
             (handle-throughput-metrics job-resources instance-runtime :succeeded pool-name)
             (handle-throughput-metrics job-resources instance-runtime :completed pool-name))
           (when (= instance-status :instance.status/failed)
             (handle-throughput-metrics job-resources instance-runtime :failed pool-name)
             (handle-throughput-metrics job-resources instance-runtime :completed pool-name)
             (when-not previous-reason
               (update-reason-metrics! db task-id reason instance-runtime job-resources)))
           (when-not (nil? instance)
             (log/debug "Transacting updated state for instance" instance "to status" instance-status)
             ;; The database can become inconsistent if we make multiple calls to :instance/update-state in a single
             ;; transaction; see the comment in the definition of :instance/update-state for more details
             (let [transaction-chan (datomic/transact-with-retries
                                      conn
                                      (reduce
                                        into
                                        [[:instance/update-state
                                          instance
                                          instance-status
                                          (or (:db/id previous-reason)
                                              (reason/mesos-reason->cook-reason-entity-id db task-id reason)
                                              [:reason.name :unknown])]]
                                        [(when (and (#{:instance.status/failed} instance-status) (not previous-reason) reason)
                                           [[:db/add
                                             instance
                                             :instance/reason
                                             (reason/mesos-reason->cook-reason-entity-id db task-id reason)]])
                                         (when (and (#{:instance.status/success
                                                       :instance.status/failed} instance-status)
                                                    (nil? (:instance/end-time instance-ent)))
                                           [[:db/add instance :instance/end-time (now)]])
                                         (when (and (#{:task-starting :task-running} task-state)
                                                    (nil? (:instance/mesos-start-time instance-ent)))
                                           [[:db/add instance :instance/mesos-start-time (now)]])
                                         (when progress
                                           [[:db/add instance :instance/progress progress]])]))]
               (async/go
                 ; Wait for the transcation to complete before running the plugin
                 (let [chan-result (async/<! transaction-chan)]
                   (when (#{:instance.status/success :instance.status/failed} instance-status)
                     (let [db (d/db conn)
                           updated-job (d/entity db job)
                           updated-instance (d/entity db instance)]
                       (try
                         (plugins/on-instance-completion completion/plugin updated-job updated-instance)
                         (catch Exception e
                           (log/error e "Error while running instance completion plugin.")))))
                   chan-result)))))
         (catch Exception e
           (log/error e "Mesos scheduler status update error")))))

(defn write-sandbox-url-to-datomic
  "Takes a sandbox file server URL from the compute cluster and saves it to datomic."
  [conn task-id sandbox-url]
  (try
    (let [db (db conn)
          _ (when-not task-id
              (throw (ex-info "task-id is nil. Something unexpected has happened."
                              {:sandbox-url sandbox-url
                               :task-id task-id})))
          [instance] (first (q '[:find ?i
                                 :in $ ?task-id
                                 :where
                                 [?i :instance/task-id ?task-id]]
                               db task-id))]
      (if (nil? instance)
        (log/error "Sandbox file server URL update error. No instance for task-id" task-id)
        @(d/transact conn [[:db/add instance :instance/sandbox-url sandbox-url]])))
    (catch Exception e
      (log/error e "Sandbox file server URL update error"))))

(defn- task-id->instance-id
  "Retrieves the instance-id given a task-id"
  [db task-id]
  (-> (d/entity db [:instance/task-id task-id])
      :db/id))

(defn handle-framework-message
  "Processes a framework message from Mesos."
  [conn {:keys [handle-exit-code handle-progress-message]}
   {:strs [exit-code progress-message progress-percent progress-sequence task-id] :as message}]
  (log/info "Received framework message:" {:task-id task-id, :message message})
  (timers/time!
    handle-framework-message-duration
    (try
      (when (str/blank? task-id)
        (throw (ex-info "task-id is empty in framework message" {:message message})))
      (when (or progress-message progress-percent)
        (handle-progress-message (d/db conn) task-id
                                 {:progress-message progress-message
                                  :progress-percent progress-percent
                                  :progress-sequence progress-sequence}))
      (when exit-code
        (log/info "Updating instance" task-id "exit-code to" exit-code)
        (handle-exit-code task-id exit-code))
      (catch Exception e
        (log/error e "Mesos scheduler framework message error")))))

(timers/deftimer [cook-mesos scheduler tx-report-queue-processing-duration])
(meters/defmeter [cook-mesos scheduler tx-report-queue-datoms])
(meters/defmeter [cook-mesos scheduler tx-report-queue-update-job-state])
(meters/defmeter [cook-mesos scheduler tx-report-queue-job-complete])
(meters/defmeter [cook-mesos scheduler tx-report-queue-tasks-killed])

(defn monitor-tx-report-queue
  "Takes an async channel that will have tx report queue elements on it"
  [tx-report-chan conn]
  (log/info "Starting tx-report-queue")
  (let [kill-chan (async/chan)
        query-db (d/db conn)
        query-basis (d/basis-t query-db)
        tasks-to-kill (q '[:find ?i
                           :in $ [?status ...]
                           :where
                           [?i :instance/status ?status]
                           [?job :job/instance ?i]
                           [?job :job/state :job.state/completed]]
                         query-db [:instance.status/unknown :instance.status/running])]
    (doseq [[task-entity-id] tasks-to-kill]
      (let [task-id (cook.task/task-entity-id->task-id query-db task-entity-id)
            compute-cluster-name (cook.task/task-entity-id->compute-cluster-name query-db task-entity-id)]
        (if-let [compute-cluster (cc/compute-cluster-name->ComputeCluster compute-cluster-name)]
          (try
            (log/info "Attempting to kill task" task-id "in" compute-cluster-name "from already completed job")
            (meters/mark! tx-report-queue-tasks-killed)
            (cc/safe-kill-task compute-cluster task-id)
            (catch Exception e
              (log/error e (str "Failed to kill task" task-id))))
          (log/warn "Unable to kill task" task-id "with unknown cluster" compute-cluster-name))))
    (async/go
      (loop []
        (async/alt!
          tx-report-chan ([tx-report]
                           (async/go
                             (timers/start-stop-time! ; Use this in go blocks, time! doesn't play nice
                               tx-report-queue-processing-duration
                               (let [{:keys [tx-data db-after]} tx-report]
                                 (when (< query-basis (d/basis-t db-after))
                                   (let [db (db conn)]
                                     (meters/mark! tx-report-queue-datoms (count tx-data))
                                     ;; Monitoring whether a job is completed.
                                     (doseq [{:keys [e a v]} tx-data]
                                       (try
                                         (when (and (= a (d/entid db :job/state))
                                                    (= v (d/entid db :job.state/completed)))
                                           (meters/mark! tx-report-queue-job-complete)
                                           (doseq [[task-entity-id]
                                                   (q '[:find ?i
                                                        :in $ ?job [?status ...]
                                                        :where
                                                        [?job :job/instance ?i]
                                                        [?i :instance/status ?status]]
                                                      db e [:instance.status/unknown
                                                            :instance.status/running])]
                                             (let [task-id (cook.task/task-entity-id->task-id db task-entity-id)
                                                   compute-cluster-name (cook.task/task-entity-id->compute-cluster-name db task-entity-id)]
                                               (if-let [compute-cluster (cc/compute-cluster-name->ComputeCluster compute-cluster-name)]
                                                 (do (log/info "Attempting to kill task" task-id "in" compute-cluster-name "due to job completion")
                                                     (meters/mark! tx-report-queue-tasks-killed)
                                                     @(d/transact conn [[:db/add task-entity-id :instance/reason
                                                                         [:reason/name :reason-killed-by-user]]])
                                                     (cc/safe-kill-task compute-cluster task-id))
                                                 (log/error "Couldn't kill task" task-id "due to no Mesos driver for compute cluster" compute-cluster-name "!")))))
                                         (catch Exception e
                                           (log/error e "Unexpected exception on tx report queue processor")))))))))
                           (recur))
          kill-chan ([_] nil))))
    #(async/close! kill-chan)))

;;; ===========================================================================
;;; API for matcher
;;; ===========================================================================


; job and resources appear to be unused, but they are used. Other code paths destructure job and resource out.
(defrecord TaskRequestAdapter [job resources cpus mem ports uuid task-id assigned-resources guuid->considerable-cotask-ids constraints scalar-requests]
  TaskRequest
  (getCPUs [_] cpus)
  ; We support disk but support different types of disk, so we set this metric to 0.0 and take care of binpacking disk in the disk-host-constraint
  (getDisk [_] 0.0)
  (getHardConstraints [_] constraints)
  (getId [_] task-id)
  (getScalarRequests [_] scalar-requests)
  (getAssignedResources [_] @assigned-resources)
  (setAssignedResources [_ v] (reset! assigned-resources v))
  (getCustomNamedResources [_] {})
  (getMemory [_] mem)
  (getNetworkMbps [_] 0.0)
  (getPorts [_] ports)
  (getSoftConstraints [_] [])
  (taskGroupName [_] uuid))

(def adjust-job-resources-for-pool-fn
  (memoize
    (let [noop (fn [job resources] resources)]
      (fn [pool-name]
        (if-let [{:keys [pool-regex adjust-job-resources-fn]} (config/job-resource-adjustments)]
          (if (re-matches pool-regex pool-name) (util/lazy-load-var adjust-job-resources-fn) noop)
          noop)))))

(defn make-task-request
  "Helper to create a TaskRequest using TaskRequestAdapter. TaskRequestAdapter implements Fenzo's TaskRequest interface
   given a job, its resources, its task-id and a function assigned-cotask-getter. assigned-cotask-getter should be a
   function that takes a group uuid and returns a set of task-ids, which correspond to the tasks that will be assigned
   during the same Fenzo scheduling cycle as the newly created TaskRequest."
  [db job pool-name & {:keys [resources task-id assigned-resources guuid->considerable-cotask-ids reserved-hosts running-cotask-cache]
                       :or {resources (tools/job-ent->resources job)
                            task-id (str (d/squuid))
                            assigned-resources (atom nil)
                            guuid->considerable-cotask-ids (constantly #{})
                            running-cotask-cache (atom (cache/fifo-cache-factory {} :threshold 1))
                            reserved-hosts #{}}}]
  (let [constraints (-> (constraints/make-fenzo-job-constraints job)
                        (conj (constraints/build-rebalancer-reservation-constraint reserved-hosts))
                        (into
                          (remove nil?
                                  (mapv (fn make-group-constraints [group]
                                          (constraints/make-fenzo-group-constraint
                                            db group #(guuid->considerable-cotask-ids (:group/uuid group)) running-cotask-cache))
                                        (:group/_job job)))))
        constraints-list (into (list) constraints)
        scalar-requests (job->scalar-request job)
        pool-specific-resources ((adjust-job-resources-for-pool-fn pool-name) job resources)]
    (->TaskRequestAdapter job resources
                          (:cpus pool-specific-resources)
                          (:mem pool-specific-resources)
                          (:ports pool-specific-resources)
                          (-> job :job/uuid str)
                          task-id assigned-resources guuid->considerable-cotask-ids constraints-list scalar-requests)))

(defn jobs->resource-maps
  "Given a collection of jobs, returns a collection
   of maps, where each map is resource-type -> amount"
  [jobs]
  (map (fn job->resource-map
         [job]
         (let [{:strs [gpus disk] :as resource-map}
               (-> job
                   tools/job-ent->resources
                   (dissoc :ports)
                   walk/stringify-keys)
               flatten-gpus
               (fn [res-map]
                 (if gpus
                   (let [env (tools/job-ent->env job)
                         gpu-model (get env "COOK_GPU_MODEL" "unspecified-gpu-model")
                         gpus-resource-key (str "gpus/" gpu-model)]
                     (-> res-map
                         (assoc gpus-resource-key gpus)
                         (dissoc "gpus")))
                   res-map))
               flatten-disk
               (fn [res-map]
                 (if disk
                   (let [{request "request", limit "limit"} disk
                         type (get disk "type" "unspecified-disk-type")]
                     (cond-> res-map
                       true (dissoc "disk")
                       (not (nil? request)) (assoc (str "disk/" type "/request") request)
                       (not (nil? limit)) (assoc (str "disk/" type "/limit") limit)))
                   res-map))]
           (-> resource-map
               flatten-gpus
               flatten-disk)))
       jobs))

(defn resource-maps->stats
  "Given a collection of maps, where each map is
  resource-type -> amount, returns a map of
  statistics with the following shape:

  {:percentiles {cpus {50 ..., 95 ..., 100 ...}
                 mem {50 ..., 95 ..., 100 ...}}
   :totals {mem ..., cpus ..., ...}}"
  [resource-maps]
  (let [resources-of-interest ["cpus" "mem"]]
    {; How does :largest-by differ from the p100 in :percentiles?
     ; :largest-by shows the full resource map for the max by mem
     ; and cpus, whereas :percentiles entries only show the number
     ; for that resource. This would tell you, for example, if the
     ; offer with the most cpus has very little mem offered.
     :largest-by (pc/map-from-keys
                   (fn percentiles
                     [resource]
                     (->> resource-maps
                          (sort-by #(get % resource))
                          last
                          tools/format-resource-map))
                   resources-of-interest)
     :percentiles (pc/map-from-keys
                    (fn percentiles
                      [resource]
                      (let [resource-values (->> resource-maps
                                                 (map #(get % resource))
                                                 (remove nil?))]
                        (-> resource-values
                            (task-stats/percentiles 50 95 100)
                            tools/format-resource-map)))
                    resources-of-interest)
     :totals (->> resource-maps
                  (reduce (partial merge-with +))
                  tools/format-resource-map)}))

(defn offers->stats
  "Given a collection of offers, returns stats about the offers"
  [offers]
  (try
    (-> offers tools/offers->resource-maps resource-maps->stats)
    (catch Exception e
      (let [message "Error collecting offer stats"]
        (log/error e message)
        message))))

(defn jobs->stats
  "Given a collection of jobs, returns stats about the jobs"
  [jobs]
  (try
    (-> jobs jobs->resource-maps resource-maps->stats)
    (catch Exception e
      (let [message "Error collecting job stats"]
        (log/error e message)
        message))))

(defn unassign-all
  "Unassigns all items in the to-unassign set with the given unassigner. Must be run with the fenzo lock held."
  [pool-name ^Action2 unassigner to-unassign]
  (doseq [{:keys [task-id hostname]} to-unassign]
    (try
      ; Fenzo tracks which tasks are on which nodes with assignment. To, e.g., make
      ; sure it can distribute tasks across hosts. This instructs fenzo that a task
      ; is no longer on a given host.
      (log/debug "In" pool-name "pool, unassigning task"
                 task-id "from" hostname)
      (.call unassigner task-id hostname)
      (catch Exception e
        (log/error e "In" pool-name "pool, failed to unassign task"
                   task-id "from" hostname)))))

(defn match-offer-to-schedule
  "Given an offer and a schedule, computes all the tasks should be launched as a result.

   A schedule is just a sorted list of tasks, and we're going to greedily assign them to
   the offer.

   Returns {:matches (list of tasks that got matched to the offer)
            :failures (list of unmatched tasks, and why they weren't matched)}"
  [db {:keys [^TaskScheduler fenzo unassign-task-set]} considerable offers rebalancer-reservation-atom pool-name]
  (if (and (-> considerable count zero?)
           (-> offers count pos?)
           (every? :reject-after-match-attempt offers))
    ; If there are 0 considerable jobs and all offers are
    ; destined to get rejected after the match attempt, we
    ; might as well skip this matching iteration.
    (do
      (log/info "In" pool-name
                "pool, skip matching (0 considerable jobs)")
      {:matches [] :failures []})
    (do
      (log/debug "In" pool-name "pool, tasks to scheduleOnce" considerable)
      (let [t (System/currentTimeMillis)
            leases (mapv #(offer/offer->lease % t) offers)
            considerable->task-id (plumbing.core/map-from-keys (fn [_] (str (d/squuid))) considerable)
            guuid->considerable-cotask-ids (tools/make-guuid->considerable-cotask-ids considerable->task-id)
            running-cotask-cache (atom (cache/fifo-cache-factory {} :threshold (max 1 (count considerable))))
            job-uuid->reserved-host (or (:job-uuid->reserved-host @rebalancer-reservation-atom) {})
            reserved-hosts (into (hash-set) (vals job-uuid->reserved-host))
            ; Important that requests maintains the same order as considerable
            requests (mapv (fn [job]
                             (make-task-request db job pool-name
                                                :guuid->considerable-cotask-ids guuid->considerable-cotask-ids
                                                :reserved-hosts (disj reserved-hosts (job-uuid->reserved-host (:job/uuid job)))
                                                :running-cotask-cache running-cotask-cache
                                                :task-id (considerable->task-id job)))
                           considerable)
            ;; Need to lock on fenzo when accessing scheduleOnce because scheduleOnce and
            ;; task assigner can not be called at the same time.
            ;; task assigner may be called when reconciling
            to-unassign (util/set-atom! unassign-task-set #{})
            ^Action2 unassigner (.getTaskUnAssigner fenzo)
            ^SchedulingResult result (locking fenzo
                                       (unassign-all pool-name unassigner to-unassign)
                                       (timers/time!
                                         (timers/timer (metric-title "fenzo-schedule-once-duration" pool-name))
                                         (.scheduleOnce fenzo requests leases)))
            failure-results (-> result .getFailures .values)
            assignments (-> result .getResultMap .values)]
        (doall (map (fn [^VirtualMachineLease lease]
                      (when (-> lease :offer :reject-after-match-attempt)
                        (locking fenzo
                          (.expireLease fenzo (.getId lease)))))
                    leases))

        (log/debug "In" pool-name "pool, found this assignment:" result)

        {:matches (mapv (fn [^VMAssignmentResult assignment]
                          {:leases (.getLeasesUsed assignment)
                           :tasks (.getTasksAssigned assignment)
                           :hostname (.getHostname assignment)})
                        assignments)
         :failures failure-results}))))

(meters/defmeter [cook-mesos scheduler scheduler-offer-declined])

(defn decline-offers
  "declines a collection of offer ids"
  [compute-cluster offer-ids]
  (log/debug "Declining offers:" offer-ids)
  (meters/mark! scheduler-offer-declined (count offer-ids))
  (try
    (cc/decline-offers compute-cluster offer-ids)
    (catch Throwable t
      (log/error t "Error declining offers for" compute-cluster))))

(histograms/defhistogram [cook-mesos scheduler number-tasks-matched])
(histograms/defhistogram [cook-mesos-scheduler number-offers-matched])
(meters/defmeter [cook-mesos scheduler scheduler-offer-matched])
(meters/defmeter [cook-mesos scheduler handle-resource-offer!-errors])
(def front-of-job-queue-mem-atom (atom 0))
(def front-of-job-queue-cpus-atom (atom 0))
(gauges/defgauge [cook-mesos scheduler front-of-job-queue-mem] (fn [] @front-of-job-queue-mem-atom))
(gauges/defgauge [cook-mesos scheduler front-of-job-queue-cpus] (fn [] @front-of-job-queue-cpus-atom))

(defn generate-user-usage-map
  "Returns a mapping from user to usage stats"
  [unfiltered-db pool-name]
  (timers/time!
    generate-user-usage-map-duration
    (->> (tools/get-running-task-ents unfiltered-db)
         (map :job/_instance)
         (remove #(not= pool-name (cached-queries/job->pool-name %)))
         (group-by :job/user)
         (pc/map-vals (fn [jobs]
                        (->> jobs
                             (map tools/job->usage)
                             (reduce (partial merge-with +))))))))

(defn pending-jobs->considerable-jobs
  "Limit the pending jobs to considerable jobs based on usage and quota.
   Further limit the considerable jobs to a maximum of num-considerable jobs."
  [db pending-jobs user->quota user->usage num-considerable pool-name]
  (log/debug "In" pool-name "pool, there are" (count pending-jobs) "pending jobs:" pending-jobs)
  (let [enforcing-job-launch-rate-limit? (ratelimit/enforce? quota/per-user-per-pool-launch-rate-limiter)
        user->rate-limit-count (atom {})
        user->passed-count (atom {})
        considerable-jobs
        (->> pending-jobs
             (tools/filter-pending-jobs-for-quota pool-name user->rate-limit-count user->passed-count
                                                  user->quota user->usage
                                                  (tools/global-pool-quota (config/pool-quotas) pool-name))
             (filter (fn [job] (tools/job-allowed-to-start? db job)))
             (filter launch-plugin/filter-job-launches)
             (take num-considerable)
             ; Force this to be taken eagerly so that the log line is accurate.
             (doall))]
    (swap! tools/pool->user->num-rate-limited-jobs update pool-name (constantly @user->rate-limit-count))
    (log/info "In" pool-name "pool, job launch rate-limiting"
              {:enforcing-job-launch-rate-limit? enforcing-job-launch-rate-limit?
               :total-rate-limit-count (->> @user->rate-limit-count vals (reduce +))
               :user->rate-limit-count @user->rate-limit-count
               :total-passed-count (->> @user->passed-count vals (reduce +))
               :user->passed-count @user->passed-count})
    considerable-jobs))


(defn matches->jobs
  "Given a collection of matches, returns the matched jobs"
  [matches]
  (->> matches
       (mapcat :tasks)
       (map #(-> ^TaskAssignmentResult % .getRequest :job))))


(defn matches->job-uuids
  "Returns the matched job uuids."
  [matches pool-name]
  (let [jobs (matches->jobs matches)
        job-uuids (set (map :job/uuid jobs))]
    (log/debug "In" pool-name "pool, matched jobs:" (count job-uuids))
    (when (seq matches)
      (let [matched-normal-jobs-resource-requirements (tools/sum-resources-of-jobs jobs)]
        (meters/mark! (meters/meter (metric-title "matched-tasks-cpus" pool-name))
                      (:cpus matched-normal-jobs-resource-requirements))
        (meters/mark! (meters/meter (metric-title "matched-tasks-mem" pool-name))
                      (:mem matched-normal-jobs-resource-requirements))))
    job-uuids))

(defn remove-matched-jobs-from-pending-jobs
  "Removes matched jobs from pool->pending-jobs."
  [pool-name->pending-jobs matched-job-uuids pool-name]
  (update-in pool-name->pending-jobs [pool-name]
             (fn [jobs]
               (remove #(contains? matched-job-uuids (:job/uuid %)) jobs))))

(defn- update-match-with-task-metadata-seq
  "Updates the match with an entry for the task metadata for all tasks. A 'match' is a set of jobs that we
  will want to all run on the same host."
  [{:keys [tasks leases] :as match} db mesos-run-as-user]
  (let [offers (mapv :offer leases)
        first-offer (-> offers first)
        compute-cluster (-> first-offer :compute-cluster)]
    (->> tasks
         ;; sort-by makes task-txns created in matches->task-txns deterministic
         (sort-by (comp :job/uuid :job #(.getRequest ^TaskAssignmentResult %)))
         (map (partial task/TaskAssignmentResult->task-metadata db mesos-run-as-user compute-cluster))
         (assoc match :task-metadata-seq))))

(defn- matches->task-txns
  "Converts matches to a task transactions."
  [matches]
  (for [{:keys [leases task-metadata-seq]} matches
        :let [offers (mapv :offer leases)
              first-offer (-> offers first)
              slave-id (-> first-offer :slave-id :value)]
        {:keys [executor hostname ports-assigned task-id task-request]} task-metadata-seq
        :let [{:keys [job/last-waiting-start-time job/uuid]} (:job task-request)
              job-ref [:job/uuid uuid]
              instance-start-time (now)]]
    [[:job/allowed-to-start? job-ref]
     ;; NB we set any job with an instance in a non-terminal
     ;; state to running to prevent scheduling the same job
     ;; twice; see schema definition for state machine
     [:db/add job-ref :job/state :job.state/running]
     (cond->
       {:db/id (d/tempid :db.part/user)
        :job/_instance job-ref
        :instance/executor executor
        :instance/executor-id task-id ;; NB command executor uses the task-id as the executor-id
        :instance/hostname hostname
        :instance/ports ports-assigned
        :instance/preempted? false
        :instance/progress 0
        :instance/slave-id slave-id
        :instance/start-time instance-start-time
        :instance/status :instance.status/unknown
        :instance/task-id task-id
        :instance/compute-cluster
        (-> first-offer
            :compute-cluster
            cc/db-id)}
       last-waiting-start-time
       (assoc :instance/queue-time
              (- (.getTime instance-start-time)
                 (.getTime ^Date last-waiting-start-time))))]))

(defn- match->compute-cluster
  "Given a match object, returns the
  compute cluster the match corresponds to"
  [{:keys [leases]}]
  (let [offers (mapv :offer leases)]
    (-> offers first :compute-cluster)))

(defn launch-matches!
  "Launches tasks for the given matches in the given compute cluster"
  [compute-cluster pool-name matches ^TaskScheduler fenzo]
  (try
    (cc/launch-tasks
      compute-cluster
      pool-name
      matches
      (fn process-task-post-launch!
        [{:keys [hostname task-request]}]
        (let [user (get-in task-request [:job :job/user])
              compute-cluster-launch-rate-limiter (cc/launch-rate-limiter compute-cluster)
              token-key (quota/pool+user->token-key pool-name user)]
          (ratelimit/spend! quota/per-user-per-pool-launch-rate-limiter token-key 1)
          (ratelimit/spend! compute-cluster-launch-rate-limiter ratelimit/compute-cluster-launch-rate-limiter-key 1))
        (locking fenzo
          (-> fenzo
              (.getTaskAssigner)
              (.call task-request hostname)))))
    (catch Throwable t
      (log/error t "In" pool-name "pool, error launching tasks for"
                 (cc/compute-cluster-name compute-cluster) "compute cluster"))))

(defn filter-matches-for-ratelimit
  "Given a set of matches, determine which compute clusters are beyond the rate limit and filter matches in those compute clusters out."
  [matches]
  (let [augmented-matches (->> matches
                               (group-by match->compute-cluster)
                               (map
                                 (fn [[compute-cluster matches-in-compute-cluster]]
                                   (let [compute-cluster-name (cc/compute-cluster-name compute-cluster)
                                         compute-cluster-launch-rate-limiter (cc/launch-rate-limiter compute-cluster)
                                         enforce? (ratelimit/enforce? compute-cluster-launch-rate-limiter)
                                         token-count (ratelimit/get-token-count!
                                                       compute-cluster-launch-rate-limiter
                                                       ratelimit/compute-cluster-launch-rate-limiter-key)
                                         resume-millis (ratelimit/time-until-out-of-debt-millis!
                                                         compute-cluster-launch-rate-limiter
                                                         ratelimit/compute-cluster-launch-rate-limiter-key)
                                         skipping-cycle? (and enforce? (neg? token-count))]
                                     (if skipping-cycle?
                                       {:skip-rate-limit true
                                        :why {:matches-skipped (count matches-in-compute-cluster)

                                              :compute-cluster compute-cluster-name
                                              :tokens-count token-count
                                              :resume-millis resume-millis}}
                                       {:skip-rate-limit false
                                        :matches matches-in-compute-cluster})))))
        matches-throttled (->> augmented-matches
                               (filter :skip-rate-limit)
                               (map :why)
                               (reduce conj [] ))
        matches-kept (->> augmented-matches
                          (remove :skip-rate-limit)
                          (map :matches)
                          (reduce concat []))]
    (when-not (empty? matches-throttled)
      (log/warn "Skipping a subset of matches because of rate-limit:" matches-throttled))
    matches-kept))

(def kill-lock-timer-for-launch (timers/timer ["cook-mesos" "scheduler" "kill-lock-acquire-for-launch"]))

(defn launch-matched-tasks!
  "Updates the state of matched tasks in the database and then launches them."
  [matches conn db fenzo mesos-run-as-user pool-name]
  (let [matches (map #(update-match-with-task-metadata-seq % db mesos-run-as-user) matches)
        task-txns (matches->task-txns matches)
        kill-lock-timer-context (timers/start kill-lock-timer-for-launch)]
    (log/info "In" pool-name "pool, writing tasks"
              {:first-10-tasks
               (take
                 10
                 (for [{:keys [task-metadata-seq]} matches
                       {:keys [task-id task-request]} task-metadata-seq]
                   {:job-uuid (-> task-request (get-in [:job :job/uuid]) str)
                    :task-id task-id}))
               :number-tasks
               (count task-txns)})
    (timers/time!
      (timers/timer (metric-title "launch-matched-tasks-all-duration" pool-name))
      ; Avoids a race. See docs for kill-lock-object.
      (try
        (.. cc/kill-lock-object readLock lock)
        ;; Determine lock acquisition time.
        (.stop kill-lock-timer-context)
        ;; Note that this transaction can fail if a job was scheduled
        ;; during a race. If that happens, then other jobs that should
        ;; be scheduled will not be eligible for rescheduling until
        ;; the pending-jobs atom is repopulated
        (timers/time!
          (timers/timer (metric-title "handle-resource-offer!-transact-task-duration" pool-name))
          (datomic/transact
            conn
            (reduce into [] task-txns)
            (fn [e]
              (log/warn e
                        "In" pool-name "pool, transaction timed out, so these tasks might be present"
                        "in Datomic without actually having been launched in Mesos"
                        matches)
              (throw e))))

        (let [offers-matched (->> matches
                                  (mapcat :leases)
                                  (map :offer))
              num-offers-matched (->> offers-matched
                                      (map (comp :value :id))
                                      distinct
                                      count)
              user->num-jobs (->> matches
                                  (mapcat :task-metadata-seq)
                                  (map (comp cached-queries/job-ent->user :job :task-request))
                                  frequencies)]
          (log/info "In" pool-name "pool, launching matched tasks"
                    {:number-offers-matched num-offers-matched
                     :number-tasks (count task-txns)
                     :user->number-jobs user->num-jobs})
          (meters/mark! scheduler-offer-matched num-offers-matched)
          (histograms/update! number-offers-matched num-offers-matched))
        (meters/mark! (meters/meter (metric-title "matched-tasks" pool-name)) (count task-txns))

        ;; Launching the matched tasks MUST happen after the above transaction in
        ;; order to allow a transaction failure (due to failed preconditions)
        ;; to block the launch
        (timers/time!
          (timers/timer (metric-title "handle-resource-offer!-mesos-submit-duration" pool-name))
          (->> (group-by match->compute-cluster matches)
               (map
                 (fn [[compute-cluster matches-in-compute-cluster]]
                   (let [compute-cluster-name (cc/compute-cluster-name compute-cluster)
                         _ (log/info "In" pool-name "pool, launching matched tasks for" compute-cluster-name "compute cluster")
                         launch-matches-in-compute-cluster!
                         #(launch-matches! compute-cluster pool-name
                                           matches-in-compute-cluster fenzo)]
                     (doseq [match matches-in-compute-cluster]
                       (timers/stop (-> match :leases first :offer :offer-match-timer)))
                     (if (:mesos-config compute-cluster)
                       (launch-matches-in-compute-cluster!)
                       (future (launch-matches-in-compute-cluster!))))))
               doall
               (run! #(when (future? %) (deref %)))))
        (finally
          (.. cc/kill-lock-object readLock unlock))))))

(defn update-host-reservations!
  "Updates the rebalancer-reservation-atom with the result of the match cycle.
   - Releases reservations for jobs that were matched
   - Adds matched job uuids to the launched-job-uuids list"
  [rebalancer-reservation-atom matched-job-uuids]
  (swap! rebalancer-reservation-atom (fn [{:keys [job-uuid->reserved-host launched-job-uuids]}]
                                       {:job-uuid->reserved-host (apply dissoc job-uuid->reserved-host matched-job-uuids)
                                        :launched-job-uuids (into launched-job-uuids matched-job-uuids)})))

(defn job->acceptable-compute-clusters
  "Given a job and a collection of compute clusters, returns the
  subset of compute clusters that the job would accept running
  (and therefore, autoscaling) on. Note that this can return an
  empty collection if no compute cluster is deemed acceptable."
  [job compute-clusters]
  (if-let [previous-location (constraints/job->last-checkpoint-location job)]
    ; We assume here that the number of compute clusters is small
    ; (~10 or less); otherwise, we'd optimize this by pre-computing
    ; the map of location -> (compute clusters in that location) and
    ; passing that pre-computed map into this function
    (filter
      #(= (cc/compute-cluster->location %)
          previous-location)
      compute-clusters)
    ; If job->last-checkpoint-location returns nil, then we can
    ; consider all of the passed compute clusters to be acceptable
    compute-clusters))

(defn distribute-jobs-to-compute-clusters
  "Given a collection of pending jobs and a collection of
  compute clusters, distributes the jobs amongst the compute
  clusters, using job->acceptable-compute-clusters-fn preferences,
  along with a hash of the pending job's uuid. Returns a
  compute-cluster->jobs map. That is the API any future
  improvements need to stick to."
  [autoscalable-jobs pool-name compute-clusters job->acceptable-compute-clusters-fn]
  (let [compute-cluster->jobs
        (group-by
          (fn choose-compute-cluster-for-autoscaling
            [{:keys [job/uuid] :as job}]
            (let [preferred-compute-clusters
                  (job->acceptable-compute-clusters-fn job compute-clusters)]
              (if (empty? preferred-compute-clusters)
                :no-acceptable-compute-cluster
                (nth preferred-compute-clusters
                     (-> uuid hash (mod (count preferred-compute-clusters)))))))
          autoscalable-jobs)]
    (when-let [jobs (:no-acceptable-compute-cluster compute-cluster->jobs)]
      (log/info "In" pool-name
                "pool, there are jobs with no acceptable compute cluster for autoscaling"
                {:first-10-jobs (->> jobs (take 10) (map :job/uuid) (map str))}))
    (dissoc compute-cluster->jobs :no-acceptable-compute-cluster)))

(defn trigger-autoscaling!
  "Autoscales the given pool to satisfy the given pending jobs, if:
  - There is at least one pending job
  - There is at least one compute cluster configured to do autoscaling"
  [pending-jobs-for-autoscaling pool-name compute-clusters job->acceptable-compute-clusters-fn]
  (timers/time!
    (timers/timer (metric-title "trigger-autoscaling!-duration" pool-name))
    (try
      (let [autoscaling-compute-clusters (filter #(cc/autoscaling? % pool-name) compute-clusters)
            num-autoscaling-compute-clusters (count autoscaling-compute-clusters)]
        (when (and (pos? num-autoscaling-compute-clusters) (seq pending-jobs-for-autoscaling))
          (log/info "In" pool-name "pool, preparing for autoscaling")
          (let [compute-cluster->jobs (distribute-jobs-to-compute-clusters
                                        pending-jobs-for-autoscaling pool-name autoscaling-compute-clusters
                                        job->acceptable-compute-clusters-fn)]
            (log/info "In" pool-name "pool, starting autoscaling")
            (->> compute-cluster->jobs
                 (map
                   (fn [[compute-cluster jobs-for-cluster]]
                     (future (cc/autoscale! compute-cluster pool-name jobs-for-cluster adjust-job-resources-for-pool-fn))))
                 doall
                 (run! deref)))
          (log/info "In" pool-name "pool, done autoscaling")))
      (catch Throwable e
        (log/error e "In" pool-name "pool, encountered error while triggering autoscaling")))))

(def pool-name->unmatched-job-uuid->unmatched-cycles-atom (atom {}))

(counters/defcounter [cook-scheduler match cycle-considerable])
(counters/defcounter [cook-scheduler match cycle-matched])
(counters/defcounter [cook-scheduler match cycle-unmatched])

(defn handle-match-cycle-metrics
  [match-map]
  (let [{:keys [considerable-jobs head-matched? head-resources matches max-considerable
                number-considerable-jobs number-matched-jobs number-unmatched-jobs offers offers-scheduled
                pool-name]} match-map
        user->number-matched-considerable-jobs (->> matches
                                                    matches->jobs
                                                    (map cached-queries/job-ent->user)
                                                    frequencies)
        user->number-total-considerable-jobs (->> considerable-jobs
                                                  (map cached-queries/job-ent->user)
                                                  frequencies)]
    (if (= number-considerable-jobs 0)
      ; keep the log slim in the 0 considerables case
      (log/info (json/write-str {:inputs {:jobs-considerable 0}
                                 :pool-name pool-name}))
      ; nonzero considerables case
      (log/info (json/write-str {:inputs {:jobs-considerable number-considerable-jobs
                                          :offers (count offers)
                                          :users user->number-total-considerable-jobs
                                          :max-considerable max-considerable
                                          :queue-was-full (= max-considerable number-considerable-jobs)}
                                 :matched {:jobs-considerable number-matched-jobs
                                           :offers (count offers-scheduled)
                                           :users user->number-matched-considerable-jobs
                                           :match-percent (/ number-matched-jobs number-considerable-jobs)
                                           :head-was-matched head-matched?}
                                 :pool-name pool-name
                                 :unmatched {:jobs-considerable number-unmatched-jobs
                                             :offers (- (count offers) (count offers-scheduled))
                                             :users (merge-with
                                                      -
                                                      user->number-total-considerable-jobs
                                                      user->number-matched-considerable-jobs)}
                                 :stats {:jobs-considerable (jobs->stats considerable-jobs)
                                         :offers (offers->stats offers)
                                         :head-resources head-resources}})))
    (counters/inc! cycle-considerable number-considerable-jobs)
    (counters/inc! cycle-matched number-matched-jobs)
    (counters/inc! cycle-unmatched number-unmatched-jobs)))

(defn handle-resource-offers!
  "Gets a list of offers from mesos. Decides what to do with them all--they should all
   be accepted or rejected at the end of the function."
  [conn fenzo-state pool-name->pending-jobs-atom mesos-run-as-user
   user->usage user->quota num-considerable offers rebalancer-reservation-atom pool-name compute-clusters
   job->acceptable-compute-clusters-fn]
  (log/debug "In" pool-name "pool, invoked handle-resource-offers!")
  (let [offer-stash (atom nil)] ;; This is a way to ensure we never lose offers fenzo assigned if an error occurs in the middle of processing
    ;; TODO: It is possible to have an offer expire by mesos because we recycle it a bunch of times.
    ;; TODO: If there is an exception before offers are sent to fenzo (scheduleOnce) then the offers will be lost. This is fine with offer expiration, but not great.
    (timers/time!
      (timers/timer (metric-title "handle-resource-offer!-duration" pool-name))
      (try
        (let [db (db conn)
              pending-jobs (get @pool-name->pending-jobs-atom pool-name)
              considerable-jobs (timers/time!
                                  (timers/timer (metric-title "handle-resource-offer!-considerable-jobs-duration" pool-name))
                                  (pending-jobs->considerable-jobs
                                    db pending-jobs user->quota user->usage num-considerable pool-name))
              ; matches is a vector of maps of {:hostname .. :leases .. :tasks}
              {:keys [matches failures]} (timers/time!
                                           (timers/timer (metric-title "handle-resource-offer!-match-duration" pool-name))
                                           (match-offer-to-schedule db fenzo-state considerable-jobs offers
                                                                    rebalancer-reservation-atom pool-name))
              matches (filter-matches-for-ratelimit matches)
              _ (log/debug "In" pool-name "pool, got matches after rate limit:" matches)
              offers-scheduled (for [{:keys [leases]} matches
                                     lease leases]
                                 (:offer lease))
              matched-job-uuids (timers/time!
                                  (timers/timer (metric-title "handle-resource-offer!-match-job-uuids-duration" pool-name))
                                  (matches->job-uuids matches pool-name))
              first-considerable-job-resources (-> considerable-jobs first tools/job-ent->resources)
              matched-considerable-jobs-head? (contains? matched-job-uuids (-> considerable-jobs first :job/uuid))

              number-matched-jobs (count matched-job-uuids)
              number-considerable-jobs (count considerable-jobs)
              number-unmatched-jobs (- number-considerable-jobs number-matched-jobs)]

          (handle-match-cycle-metrics {:considerable-jobs considerable-jobs
                                       :head-matched? matched-considerable-jobs-head?
                                       :head-resources first-considerable-job-resources
                                       :matches matches
                                       :max-considerable num-considerable
                                       :number-considerable-jobs number-considerable-jobs
                                       :number-matched-jobs number-matched-jobs
                                       :number-unmatched-jobs number-unmatched-jobs
                                       :offers offers
                                       :offers-scheduled offers-scheduled
                                       :pool-name pool-name})

          ; We want to log warnings when jobs have gone unmatched for a long time.
          ; In order to do this, we keep track, per pool, of the jobs that did not
          ; get matched to an offer, along with how many matching cycles they've
          ; gone unmatched for. The amount of data we store is relatively small;
          ; it's O(# pools * # considerable jobs). If a job uuid does get matched,
          ; we stop storing it. We never store job uuids that were not considerable
          ; in the first place.
          (let [unmatched-job-uuids
                (set/difference
                  (->> considerable-jobs (map :job/uuid) set)
                  (set matched-job-uuids))
                ; There are two configuration knobs we can tweak:
                ; - unmatched-cycles-warn-threshold:
                ;   the # of consecutive unmatched matching cycles we care about
                ; - unmatched-fraction-warn-threshold:
                ;   the fraction of considerable jobs that have gone unmatched for
                ;   at least unmatched-cycles-warn-threshold beyond which we will
                ;   warn
                {:keys [unmatched-cycles-warn-threshold
                        unmatched-fraction-warn-threshold]}
                (config/offer-matching)]
            (swap!
              ; This atom's value is a map of the following shape:
              ;
              ; {"pool-1" {job-uuid-a count-a
              ;            job-uuid-b count-b
              ;            ...}
              ;  "pool-2" {job-uuid-c count-c
              ;            job-uuid-d count-d
              ;            ...}
              ; ...}
              ;
              ; where the counts are the numbers of consecutive
              ; matching cycles that the job has gone unmatched
              pool-name->unmatched-job-uuid->unmatched-cycles-atom
              (fn [m]
                (let [; Note that this doesn't leak jobs and grow
                      ; forever. We build a new map from scratch
                      ; of size at most (count unmatched-job-uuids),
                      ; which is <= num-considerable. That new map
                      ; gets assoc'ed in, replacing the existing
                      ; job-uuid -> unmatched-cycles sub-map, which
                      ; means we won't leak historic jobs.
                      unmatched-job-uuid->unmatched-cycles
                      (pc/map-from-keys
                        (fn [job-uuid]
                          (-> m
                              (get pool-name)
                              (get job-uuid 0)
                              inc))
                        unmatched-job-uuids)
                      ; Filter the map of job-uuid -> cycle-count
                      ; down to only those entries where the # of
                      ; cycles is greater than the threshold
                      unmatched-too-long
                      (filter
                        (fn [[_ cycles]]
                          (> cycles
                             unmatched-cycles-warn-threshold))
                        unmatched-job-uuid->unmatched-cycles)]
                  (when
                    (and
                      ; If there are no considerable jobs,
                      ; then this warning is not applicable
                      (pos? (count considerable-jobs))
                      ; We only want to warn then the fraction of
                      ; considerable jobs that are unmatched for
                      ; too long (too many consecutive cycles) is
                      ; greater than the configured threshold
                      (-> unmatched-too-long
                          count
                          (/ (count considerable-jobs))
                          (> unmatched-fraction-warn-threshold)))
                    ; Including the first 10 job uuids that have gone unmatched for too
                    ; long can help in troubleshooting the issue when this happens
                    (log/warn "In" pool-name "pool, jobs are unmatched for too long"
                              {:first-10-unmatched-too-long (take 10 unmatched-too-long)
                               :number-considerable (count considerable-jobs)
                               :number-unmatched-too-long (count unmatched-too-long)
                               :unmatched-cycles-warn-threshold unmatched-cycles-warn-threshold
                               :unmatched-fraction-warn-threshold unmatched-fraction-warn-threshold}))
                  ; We need to update the overall map so that we update the
                  ; job-uuid -> cycle-count state from iteration to iteration
                  (assoc
                    m
                    pool-name
                    unmatched-job-uuid->unmatched-cycles)))))

          (fenzo/record-placement-failures! conn failures)

          (reset! offer-stash offers-scheduled)
          (reset! front-of-job-queue-mem-atom (or (:mem first-considerable-job-resources) 0))
          (reset! front-of-job-queue-cpus-atom (or (:cpus first-considerable-job-resources) 0))

          (let [matched-head-or-no-matches?
                ;; Possible innocuous reasons for no matches: no offers, or no pending jobs.
                ;; Even beyond that, if Fenzo fails to match ANYTHING, "penalizing" it in the form of giving
                ;; it fewer jobs to look at is unlikely to improve the situation.
                ;; "Penalization" should only be employed when Fenzo does successfully match,
                ;; but the matches don't align with Cook's priorities.
                (if (empty? matches)
                  true
                  (do
                    (swap! pool-name->pending-jobs-atom
                           remove-matched-jobs-from-pending-jobs
                           matched-job-uuids pool-name)
                    (log/debug "In" pool-name
                               "pool, updated pool-name->pending-jobs-atom:"
                               @pool-name->pending-jobs-atom)
                    (launch-matched-tasks! matches conn db (:fenzo fenzo-state) mesos-run-as-user pool-name)
                    (update-host-reservations! rebalancer-reservation-atom matched-job-uuids)
                    matched-considerable-jobs-head?))
                ; Absolute maximum jobs we will consider autoscaling to.
                {:keys [max-jobs-for-autoscaling autoscaling-scale-factor]} (config/kubernetes)
                ; The fraction of jobs we tried to match that didn't actually get matched.
                fraction-unmatched-jobs (if (pos? number-considerable-jobs) (/ (float number-unmatched-jobs) number-considerable-jobs) 0)
                ; We want to autoscale any unmatched job
                ;     OR
                ; we want to scale our max-jobs-for-autoscaling by the fraction of the jobs we weren't able to just match.
                ; E.g. If we didn't match 20% of the queue, then we want to autoscale to 20% of max-jobs-for-autoscaling.
                ; We include a scale factor however, so that if we don't match 20% and scale factor is 2.5, we'll generate
                ; pods for 50% of max-jobs-for-autoscaling.
                ; We do this to vary our aggression for autoscaling based on how well we're matching jobs on our existing resources.
                ; If we're matching most of the jobs in the queue then we don't need to autoscale much. If we are not matching anything
                ; then we want to autoscale maximally aggressively.
                max-jobs-for-autoscaling-scaled (-> fraction-unmatched-jobs
                                                    (* autoscaling-scale-factor)
                                                    (min 1) ; Can't match more than 100% of max-jobs-for-autoscaling.
                                                    (* max-jobs-for-autoscaling)
                                                    int
                                                    (max number-unmatched-jobs)) ; Autoscale at least the pods that failed to match.
                ;; We need to filter pending jobs based on quota so that we don't
                ;; trigger autoscaling beyond what users have quota to actually run
                autoscalable-jobs (->> pool-name
                                       (get @pool-name->pending-jobs-atom)
                                       (tools/filter-pending-jobs-for-quota pool-name (atom {}) (atom {})
                                                                            user->quota user->usage (tools/global-pool-quota (config/pool-quotas) pool-name))
                                       (take max-jobs-for-autoscaling-scaled))
                filtered-autoscalable-jobs (remove #(.getIfPresent caches/recent-synthetic-pod-job-uuids (:job/uuid %)) autoscalable-jobs)]
            ; When we have at least a minimum number of jobs being looked at, metric which fraction have matched.
            ; This lets us measure how well we're matching on existing resources.
            ; We only measure when there's a minimum number of jobs being considered so that our measurements are less noisy.
            (when (> number-considerable-jobs (:considerable-job-threshold-to-collect-job-match-statistics (config/offer-matching)))
              (histograms/update! (histograms/histogram (metric-title "fraction-unmatched-jobs" pool-name)) fraction-unmatched-jobs))
            (when (pos? number-considerable-jobs)
              (log/info "In" pool-name "pool, autoscaling variables" {:autoscalable-jobs (count autoscalable-jobs)
                                                                      :filtered-autoscalable-jobs (count filtered-autoscalable-jobs)
                                                                      :fraction-unmatched-jobs fraction-unmatched-jobs
                                                                      :max-jobs-for-autoscaling-scaled max-jobs-for-autoscaling-scaled
                                                                      :number-considerable-jobs number-considerable-jobs
                                                                      :number-unmatched-jobs number-unmatched-jobs})
              ;; This call needs to happen *after* launch-matched-tasks!
              ;; in order to avoid autoscaling tasks taking up available
              ;; capacity that was already matched for real Cook tasks.
              (trigger-autoscaling! filtered-autoscalable-jobs pool-name compute-clusters job->acceptable-compute-clusters-fn))
            matched-head-or-no-matches?))
        (catch Throwable t
          (meters/mark! handle-resource-offer!-errors)
          (log/error t "In" pool-name "pool, error in match:" (ex-data t))
          (when-let [offers @offer-stash]
            ; Group the set of all offers by compute cluster and route them to that compute cluster for restoring.
            (doseq [[compute-cluster offer-subset] (group-by :compute-cluster offers)]
              (try
                (cc/restore-offers compute-cluster pool-name offer-subset)
                (catch Throwable t
                  (log/error t "For" pool-name "error restoring offers for compute cluster" compute-cluster)))))
          ; if an error happened, it doesn't mean we need to penalize Fenzo
          true)))))

(defn view-incubating-offers
  [^TaskScheduler fenzo]
  (let [pending-offers (for [^VirtualMachineCurrentState state (locking fenzo (.getVmCurrentStates fenzo))
                             :let [lease (.getCurrAvailableResources state)]
                             :when lease]
                         {:hostname (.hostname lease)
                          :slave-id (.getVMID lease)
                          :resources (.getScalarValues lease)})]
    (log/debug "We have" (count pending-offers) "pending offers")
    pending-offers))

(def fenzo-num-considerable-atom (atom 0))
(gauges/defgauge [cook-mesos scheduler fenzo-num-considerable] (fn [] @fenzo-num-considerable-atom))
(counters/defcounter [cook-mesos scheduler iterations-at-fenzo-floor])
(meters/defmeter [cook-mesos scheduler fenzo-abandon-and-reset-meter])
(counters/defcounter [cook-mesos scheduler offer-chan-depth])

(defn make-offer-handler
  "Make the core scheduling loop for a pool"
  [conn fenzo-state pool-name->pending-jobs-atom agent-attributes-cache max-considerable scaleback
   floor-iterations-before-warn floor-iterations-before-reset trigger-chan rebalancer-reservation-atom
   mesos-run-as-user pool-name cluster-name->compute-cluster-atom job->acceptable-compute-clusters-fn]
  (let [fenzo (:fenzo fenzo-state)
        resources-atom (atom (view-incubating-offers fenzo))]
    (reset! fenzo-num-considerable-atom max-considerable)
    (tools/chime-at-ch
      trigger-chan
      (fn match-jobs-event []
        (log/info "In" pool-name "pool, starting offer matching")
        (timers/time!
          (timers/timer (metric-title "match-jobs-event" pool-name))
          (let [num-considerable @fenzo-num-considerable-atom
                next-considerable
                (try
                  (let [
                        ;; There are implications to generating the user->usage here:
                        ;;  1. Currently cook has two oddities in state changes.
                        ;;  We plan to correct both of these but are important for the time being.
                        ;;    a. Cook doesn't mark as a job as running when it schedules a job.
                        ;;       While this is technically correct, it confuses some process.
                        ;;       For example, it will mean that the user->usage generated here
                        ;;       may not include jobs that have been scheduled but haven't started.
                        ;;       Since we do the filter for quota first, this is ok because those jobs
                        ;;       show up in the queue. However, it is important to know about
                        ;;    b. Cook doesn't update the job state when cook hears from mesos about the
                        ;;       state of an instance. Cook waits until it hears from datomic about the
                        ;;       instance state change to change the state of the job. This means that it
                        ;;       is possible to have large delays between when an instance changes status
                        ;;       and the job reflects that change
                        ;;  2. Once the above two items are addressed, user->usage should always correctly
                        ;;     reflect *Cook*'s understanding of the state of the world at this point.
                        ;;     When this happens, users should never exceed their quota
                        user->usage-future (future (generate-user-usage-map (d/db conn) pool-name))
                        ;; Try to clear the channel
                        ;; Merge the pending offers from all compute clusters.
                        compute-clusters (vals @cook.compute-cluster/cluster-name->compute-cluster-atom)
                        offers (apply concat (map (fn [compute-cluster]
                                                    (try
                                                      (cc/pending-offers compute-cluster pool-name)
                                                      (catch Throwable t
                                                        (log/error t "In" pool-name
                                                                   "pool, error getting pending offers for"
                                                                   (cc/compute-cluster-name compute-cluster))
                                                        (list))))
                                                  compute-clusters))
                        _ (doseq [offer offers
                                  :let [slave-id (-> offer :slave-id :value)]]
                            ; Cache offers for rebalancer so it can use job constraints when doing preemption decisions.
                            ; Computing get-offer-attr-map is pretty expensive because it includes calculating
                            ; currently running pods, so we have to union the set of pods k8s says are there and
                            ; the set of pods we're trying to put on the node. Even though it's not used by
                            ; rebalancer (and not needed). So it's OK if it's stale, so we do not need to refresh
                            ; and only store if it is a new node.
                            (when-not (ccache/get-if-present agent-attributes-cache identity slave-id)
                              (ccache/put-cache! agent-attributes-cache identity slave-id (offer/get-offer-attr-map offer))))
                        using-pools? (not (nil? (config/default-pool)))
                        user->quota (quota/create-user->quota-fn (d/db conn) (if using-pools? pool-name nil))
                        matched-head? (handle-resource-offers! conn fenzo-state pool-name->pending-jobs-atom
                                                               mesos-run-as-user @user->usage-future user->quota
                                                               num-considerable offers
                                                               rebalancer-reservation-atom pool-name compute-clusters
                                                               job->acceptable-compute-clusters-fn)]
                    (when (seq offers)
                      (reset! resources-atom (view-incubating-offers fenzo)))
                    ;; This check ensures that, although we value Fenzo's optimizations,
                    ;; we also value Cook's sensibility of fairness when deciding which jobs
                    ;; to schedule.  If Fenzo produces a set of matches that doesn't include
                    ;; Cook's highest-priority job, on the next cycle, we give Fenzo it less
                    ;; freedom in the form of fewer jobs to consider.
                    (if matched-head?
                      max-considerable
                      (let [new-considerable (max 1 (long (* scaleback num-considerable)))] ;; With max=1000 and 1 iter/sec, this will take 88 seconds to reach 1
                        (log/info "In" pool-name "pool, failed to match head, reducing number of considerable jobs"
                                  {:prev-considerable num-considerable
                                   :new-considerable new-considerable
                                   :pool pool-name})
                        new-considerable)))
                  (catch Exception e
                    (log/error e "In" pool-name "pool, offer handler encountered exception; continuing")
                    max-considerable))]

            (if (= next-considerable 1)
              (counters/inc! iterations-at-fenzo-floor)
              (counters/clear! iterations-at-fenzo-floor))

            (if (>= (counters/value iterations-at-fenzo-floor) floor-iterations-before-warn)
              (log/warn "In" pool-name "pool, offer handler has been showing Fenzo only 1 job for"
                        (counters/value iterations-at-fenzo-floor) "iterations"))

            (reset! fenzo-num-considerable-atom
                    (if (>= (counters/value iterations-at-fenzo-floor) floor-iterations-before-reset)
                      (do
                        (log/error "In" pool-name "pool, FENZO CANNOT MATCH THE MOST IMPORTANT JOB."
                                   "Fenzo has seen only 1 job for" (counters/value iterations-at-fenzo-floor)
                                   "iterations, and still hasn't matched it.  Cook is now giving up and will "
                                   "now give Fenzo" max-considerable "jobs to look at.")
                        (meters/mark! fenzo-abandon-and-reset-meter)
                        max-considerable)
                      next-considerable))))
        (log/info "In" pool-name "pool, done with offer matching"))
      {:error-handler (fn [ex] (log/error ex "In" pool-name "pool, error occurred in match"))})
    resources-atom))

(defn reconcile-jobs
  "Ensure all jobs saw their final state change"
  [conn]
  (let [jobs (map first (q '[:find ?j
                             :in $ [?status ...]
                             :where
                             [?j :job/state ?status]]
                           (db conn) [:job.state/waiting
                                      :job.state/running]))]
    (doseq [js (partition-all 25 jobs)]
      (async/<!! (datomic/transact-with-retries conn
                                                (mapv (fn [j]
                                                        [:job/update-state j])
                                                      js))))))

;; TODO test that this fenzo recovery system actually works
(defn reconcile-tasks
  "Finds all non-completed tasks, and has Mesos let us know if any have changed."
  [db compute-cluster driver pool-name->fenzo-state]
  (let [running-tasks (q '[:find ?task-id ?status ?slave-id
                           :in $ [?status ...] ?compute-cluster-id
                           :where
                           [?i :instance/status ?status]
                           [?i :instance/task-id ?task-id]
                           [?i :instance/slave-id ?slave-id]
                           [?i :instance/compute-cluster ?compute-cluster-id]]
                         db
                         [:instance.status/unknown
                          :instance.status/running]
                         (cc/db-id compute-cluster))
        sched->mesos {:instance.status/unknown :task-staging
                      :instance.status/running :task-running}]
    (when (seq running-tasks)
      (log/info "Preparing to reconcile" (count running-tasks) "tasks")
      ;; TODO: When turning on periodic reconcilation, probably want to move this to startup
      (let [processed-tasks (->> (for [task running-tasks
                                       :let [[task-id] task
                                             task-ent (d/entity db [:instance/task-id task-id])
                                             hostname (:instance/hostname task-ent)]]
                                   (when-let [job (tools/job-ent->map (:job/_instance task-ent))]
                                     (let [pool-name (cached-queries/job->pool-name job)
                                           task-request (make-task-request db job pool-name :task-id task-id)
                                           {:keys [^TaskScheduler fenzo unassign-task-set]} (pool-name->fenzo-state pool-name)
                                           to-unassign (util/set-atom! unassign-task-set #{})
                                           ^Action2 unassigner (.getTaskUnAssigner fenzo)]
                                       ;; Need to lock on fenzo when accessing taskAssigner because taskAssigner and
                                       ;; scheduleOnce can not be called at the same time.
                                       (locking fenzo
                                         (unassign-all pool-name unassigner to-unassign)
                                         (.. fenzo
                                             (getTaskAssigner)
                                             (call task-request hostname)))
                                       task)))
                                 (remove nil?))]
        (when (not= (count running-tasks) (count processed-tasks))
          (log/error "Skipping reconciling" (- (count running-tasks) (count processed-tasks)) "tasks"))
        (doseq [ts (partition-all 50 processed-tasks)]
          (log/info "Reconciling" (count ts) "tasks, including task" (first ts))
          (try
            (mesos/reconcile-tasks driver (mapv (fn [[task-id status slave-id]]
                                                  {:task-id {:value task-id}
                                                   :state (sched->mesos status)
                                                   :slave-id {:value slave-id}})
                                                ts))
            (catch Exception e
              (log/error e "Reconciliation error")))))
      (log/info "Finished reconciling all tasks"))))

;; Unfortunately, clj-time.core/millis only accepts ints, not longs.
;; The Period class has a constructor that accepts "long milliseconds",
;; but since that isn't exposed through the clj-time API, we have to call it directly.
(defn- millis->period
  "Create a time period (duration) from a number of milliseconds."
  [millis]
  (org.joda.time.Period. (long millis)))

(defn get-lingering-tasks
  "Return a list of lingering tasks.

   A lingering task is a task that runs longer than timeout-hours."
  [db now max-timeout-hours default-timeout-hours]
  (let [jobs-with-max-runtime
        (q '[:find ?i ?start-time ?max-runtime
             :in $ ?default-runtime
             :where
             [(ground [:instance.status/unknown :instance.status/running]) [?status ...]]
             [?i :instance/status ?status]
             [?i :instance/start-time ?start-time]
             [?j :job/instance ?i]
             [(get-else $ ?j :job/max-runtime ?default-runtime) ?max-runtime]]
           db (-> default-timeout-hours time/hours time/in-millis))
        max-allowed-timeout-ms (-> max-timeout-hours time/hours time/in-millis)]
    (for [[task-entity start-time max-runtime-ms] jobs-with-max-runtime
          :let [timeout-period (millis->period (min max-runtime-ms max-allowed-timeout-ms))
                timeout-boundary (time/plus (tc/from-date start-time) timeout-period)]
          :when (time/after? now timeout-boundary)]
      (d/entity db task-entity))))

(defn kill-lingering-tasks
  [now conn config]
  (let [{:keys [max-timeout-hours
                default-timeout-hours
                timeout-hours]} config
        db (d/db conn)
        ;; These defaults are for backwards compatibility
        max-timeout-hours (or max-timeout-hours timeout-hours)
        default-timeout-hours (or default-timeout-hours timeout-hours)
        lingering-tasks (get-lingering-tasks db now max-timeout-hours default-timeout-hours)]
    (when (seq lingering-tasks)
      (log/info "Starting to kill lingering jobs running more than their max-runtime."
                {:default-timeout-hours default-timeout-hours
                 :max-timeout-hours max-timeout-hours}
                "There are in total" (count lingering-tasks) "lingering tasks.")
      (doseq [task-entity lingering-tasks]
        (let [task-id (:instance/task-id task-entity)]
          (log/info "Killing lingering task" task-id)
          ;; Note that we probably should update db to mark a task failed as well.
          ;; However in the case that we fail to kill a particular task in Mesos,
          ;; we could lose the chances to kill this task again.
          (cc/kill-task-if-possible (cook.task/task-ent->ComputeCluster task-entity) task-id)
          ;; BUG - the following transaction races with the update that is triggered
          ;; when the task is actually killed and sends its exit status code.
          ;; See issue #515 on GitHub.
          @(d/transact
             conn
             [[:instance/update-state (:db/id task-entity) :instance.status/failed [:reason/name :max-runtime-exceeded]]
              [:db/add [:instance/task-id task-id] :instance/reason [:reason/name :max-runtime-exceeded]]]))))))

; Should not use driver as an argument.
(defn lingering-task-killer
  "Periodically kill lingering tasks.

   The config is a map with optional keys where
   :timout-hours specifies the timeout hours for lingering tasks"
  [conn config trigger-chan]
  (let [config (merge {:timeout-hours (* 2 24)}
                      config)]
    (tools/chime-at-ch trigger-chan
                       (fn kill-linger-task-event []
                         (kill-lingering-tasks (time/now) conn config))
                       {:error-handler (fn [e]
                                         (log/error e "Failed to reap timeout tasks!"))})))

(defn handle-stragglers
  "Searches for running jobs in groups and runs the associated straggler handler"
  [conn kill-task-fn]
  (let [running-task-ents (tools/get-running-task-ents (d/db conn))
        running-job-ents (map :job/_instance running-task-ents)
        groups (distinct (mapcat :group/_job running-job-ents))]
    (doseq [group groups]
      (log/debug "Checking group " (d/touch group) " for stragglers")

      (let [straggler-task-ents (group/find-stragglers group)]
        (log/debug "Group " group " had stragglers: " straggler-task-ents)

        (doseq [{task-ent-id :db/id :as task-ent} straggler-task-ents]
          (log/info "Killing " task-ent " of group " (:group/uuid group) " because it is a straggler")
          ;; Mark as killed first so that if we fail after this it is still marked failed
          @(d/transact
             conn
             [[:instance/update-state task-ent-id :instance.status/failed [:reason/name :straggler]]
              [:db/add task-ent-id :instance/reason [:reason/name :straggler]]])
          (kill-task-fn task-ent))))))

(defn straggler-handler
  "Periodically checks for running jobs that are in groups and runs the associated
   straggler handler."
  [conn trigger-chan]
  (tools/chime-at-ch trigger-chan
                     (fn straggler-handler-event []
                       (handle-stragglers conn (fn [task-ent]
                                                 (cc/kill-task-if-possible (cook.task/task-ent->ComputeCluster task-ent)
                                                                           (:instance/task-id task-ent)))))
                     {:error-handler (fn [e]
                                       (log/error e "Failed to handle stragglers"))}))

(defn killable-cancelled-tasks
  [db]
  (->> (q '[:find ?i
            :in $ [?status ...]
            :where
            [?i :instance/cancelled true]
            [?i :instance/status ?status]]
          db [:instance.status/running :instance.status/unknown])
       (map (fn [[x]] (d/entity db x)))))

(timers/deftimer [cook-mesos scheduler killing-cancelled-tasks-duration])

(defn cancelled-task-killer
  "Every trigger, kill tasks that have been cancelled (e.g. via the API)."
  [conn trigger-chan]
  (tools/chime-at-ch
    trigger-chan
    (fn cancelled-task-killer-event []
      (timers/time!
        killing-cancelled-tasks-duration
        (doseq [{:keys [db/id instance/task-id] :as task} (killable-cancelled-tasks (d/db conn))]
          (log/info "Killing cancelled task" task-id)
          @(d/transact conn [[:db/add id :instance/reason
                              [:reason/name :reason-killed-by-user]]])
          (cc/kill-task-if-possible (cook.task/task-ent->ComputeCluster task) task-id))))
    {:error-handler (fn [e]
                      (log/error e "Failed to kill cancelled tasks!"))}))

(defn get-user->used-resources
  "Return a map from user'name to his allocated resources, in the form of
   {:cpus cpu :mem mem}
   If a user does NOT has any running jobs, then there is NO such
   user in this map.

   (get-user-resource-allocation [db user])
   Return a map from user'name to his allocated resources, in the form of
   {:cpus cpu :mem mem}
   If a user does NOT has any running jobs, all the values in the
   resource map is 0.0"
  ([db]
   (let [user->used-resources (->> (q '[:find ?j
                                        :in $
                                        :where
                                        [?j :job/state :job.state/running]]
                                      db)
                                   (map (fn [[eid]]
                                          (d/entity db eid)))
                                   (group-by :job/user)
                                   (map (fn [[user job-ents]]
                                          [user (tools/sum-resources-of-jobs job-ents)]))
                                   (into {}))]
     user->used-resources))
  ([db user]
   (let [used-resources (->> (q '[:find ?j
                                  :in $ ?u
                                  :where
                                  [?j :job/state :job.state/running]
                                  [?j :job/user ?u]]
                                db user)
                             (map (fn [[eid]]
                                    (d/entity db eid)))
                             (tools/sum-resources-of-jobs))]
     {user (if (seq used-resources)
             used-resources
             ;; Return all 0's for a user who does NOT have any running job.
             (zipmap (queries/get-all-resource-types db) (repeat 0.0)))})))

(defn limit-over-quota-jobs
  "Filters task-ents, preserving at most (config/max-over-quota-jobs) that would exceed the user's quota"
  [task-ents quota]
  (let [over-quota-job-limit (config/max-over-quota-jobs)]
    (->> task-ents
         (map (fn [task-ent] [task-ent (tools/job->usage (:job/_instance task-ent))]))
         (reductions (fn [[prev-task total-usage over-quota-jobs] [task-ent usage]]
                       (let [total-usage' (merge-with + total-usage usage)]
                         (if (tools/below-quota? quota total-usage')
                           [task-ent total-usage' over-quota-jobs]
                           [task-ent total-usage' (inc over-quota-jobs)])))
                     [nil {} 0])
         (take-while (fn [[task-ent _ over-quota-jobs]] (<= over-quota-jobs over-quota-job-limit)))
         (map first)
         (filter (fn [task-ent] (not (nil? task-ent)))))))

(defn sort-jobs-by-dru-helper
  "Return a list of job entities ordered by the provided sort function"
  [pending-task-ents running-task-ents user->dru-divisors sort-task-scored-task-pairs sort-jobs-duration pool-name user->quota]
  (let [tasks (into (vec running-task-ents) pending-task-ents)
        task-comparator (tools/same-user-task-comparator tasks)
        pending-task-ents-set (into #{} pending-task-ents)
        jobs (timers/time!
               sort-jobs-duration
               (->> tasks
                    (group-by tools/task-ent->user)
                    (map (fn [[user task-ents]] (let [sorted-tasks (sort task-comparator task-ents)]
                                                  [user (limit-over-quota-jobs sorted-tasks (user->quota user))])))
                    (into (hash-map))
                    (sort-task-scored-task-pairs user->dru-divisors pool-name)
                    (filter (fn [[task _]] (contains? pending-task-ents-set task)))
                    (map (fn [[task _]] (:job/_instance task)))))]
    jobs))

(defn- sort-normal-jobs-by-dru
  "Return a list of normal job entities ordered by dru"
  [pending-task-ents running-task-ents user->dru-divisors timer pool-name user->quota]
  (sort-jobs-by-dru-helper pending-task-ents running-task-ents user->dru-divisors
                           dru/sorted-task-scored-task-pairs timer pool-name user->quota))

(defn- sort-gpu-jobs-by-dru
  "Return a list of gpu job entities ordered by dru"
  [pending-task-ents running-task-ents user->dru-divisors timer pool-name user->quota]
  (sort-jobs-by-dru-helper pending-task-ents running-task-ents user->dru-divisors
                           dru/sorted-task-cumulative-gpu-score-pairs timer pool-name user->quota))

(defn- pool-map
  "Given a collection of pools, and a function val-fn that takes a pool,
  returns a map from pool name to (val-fn pool)"
  [pools val-fn]
  (->> pools
       (pc/map-from-keys val-fn)
       (pc/map-keys :pool/name)))

(defn sort-jobs-by-dru-pool
  "Returns a map from job pool to a list of job entities, ordered by dru"
  [unfiltered-db]
  ;; This function does not use the filtered db when it is not necessary in order to get better performance
  ;; The filtered db is not necessary when an entity could only arrive at a given state if it was already committed
  ;; e.g. running jobs or when it is always considered committed e.g. shares
  ;; The unfiltered db can also be used on pending job entities once the filtered db is used to limit
  ;; to only those jobs that have been committed.
  (let [pool-name->pending-job-ents (group-by cached-queries/job->pool-name (queries/get-pending-job-ents unfiltered-db))
        pool-name->pending-task-ents (pc/map-vals #(map tools/create-task-ent %1) pool-name->pending-job-ents)
        pool-name->running-task-ents (group-by (comp cached-queries/job->pool-name :job/_instance)
                                               (tools/get-running-task-ents unfiltered-db))
        pools (->> unfiltered-db pool/all-pools (filter pool/schedules-jobs?))
        using-pools? (-> pools count pos?)
        pool-name->user->dru-divisors (if using-pools?
                                        (pool-map pools (fn [{:keys [pool/name]}]
                                                          (share/create-user->share-fn unfiltered-db name)))
                                        {"no-pool" (share/create-user->share-fn unfiltered-db nil)})
        pool-name->sort-jobs-by-dru-fn (if using-pools?
                                         (pool-map pools (fn [{:keys [pool/dru-mode]}]
                                                           (case dru-mode
                                                             :pool.dru-mode/default sort-normal-jobs-by-dru
                                                             :pool.dru-mode/gpu sort-gpu-jobs-by-dru)))
                                         {"no-pool" sort-normal-jobs-by-dru})]
    (letfn [(sort-jobs-by-dru-pool-helper [[pool-name sort-jobs-by-dru]]
              (let [pending-tasks (pool-name->pending-task-ents pool-name)
                    running-tasks (pool-name->running-task-ents pool-name)
                    user->dru-divisors (pool-name->user->dru-divisors pool-name)
                    user->quota (quota/create-user->quota-fn unfiltered-db
                                                             (when using-pools? pool-name))
                    timer (timers/timer (metric-title "sort-jobs-hierarchy-duration" pool-name))]
                [pool-name (sort-jobs-by-dru pending-tasks running-tasks user->dru-divisors timer pool-name user->quota)]))]
      (into {} (map sort-jobs-by-dru-pool-helper) pool-name->sort-jobs-by-dru-fn))))

(timers/deftimer [cook-mesos scheduler filter-offensive-jobs-duration])

(defn is-offensive?
  [max-memory-mb max-cpus job]
  (let [{memory-mb :mem
         cpus :cpus} (tools/job-ent->resources job)]
    (or (> memory-mb max-memory-mb)
        (> cpus max-cpus))))

(defn filter-offensive-jobs
  "Base on the constraints on memory and cpus, given a list of job entities it
   puts the offensive jobs into offensive-job-ch asynchronically and returns
   the inoffensive jobs.

   A job is offensive if and only if its required memory or cpus exceeds the
   limits"
  ;; TODO these limits should come from the largest observed host from Fenzo
  ;; .getResourceStatus on TaskScheduler will give a map of hosts to resources; we can compute the max over those
  [{max-memory-gb :memory-gb max-cpus :cpus} offensive-jobs-ch jobs]
  (timers/time!
    filter-offensive-jobs-duration
    (let [max-memory-mb (* 1024.0 max-memory-gb)
          is-offensive? (partial is-offensive? max-memory-mb max-cpus)
          inoffensive (remove is-offensive? jobs)
          offensive (filter is-offensive? jobs)]
      ;; Put offensive jobs asynchronically such that it could return the
      ;; inoffensive jobs immediately.
      (async/go
        (when (seq offensive)
          (log/info "Found" (count offensive) "offensive jobs")
          (async/>! offensive-jobs-ch offensive)))
      inoffensive)))

(defn make-offensive-job-stifler
  "It returns an async channel which will be used to receive offensive jobs expected
   to be killed / aborted.

   It asynchronically pulls offensive jobs from the channel and abort these
   offensive jobs by marking job state as completed."
  [conn]
  (let [offensive-jobs-ch (async/chan (async/sliding-buffer 256))]
    (async/thread
      (loop []
        (when-let [offensive-jobs (async/<!! offensive-jobs-ch)]
          (try
            (doseq [jobs (partition-all 32 offensive-jobs)]
              ;; Transact synchronously so that it won't accidentally put a huge
              ;; spike of load on the transactor.
              (async/<!!
                (datomic/transact-with-retries conn
                                               (mapv
                                                 (fn [job]
                                                   [:db/add [:job/uuid (:job/uuid job)]
                                                    :job/state :job.state/completed])
                                                 jobs))))
            (log/warn "Suppressed offensive" (count offensive-jobs) "jobs" (mapv :job/uuid offensive-jobs))
            (catch Exception e
              (log/error e "Failed to kill the offensive job!")))
          (recur))))
    offensive-jobs-ch))

(timers/deftimer [cook-mesos scheduler rank-jobs-duration])
(meters/defmeter [cook-mesos scheduler rank-jobs-failures])

(defn rank-jobs
  "Return a map of lists of job entities ordered by dru, keyed by pool.

   It ranks the jobs by dru first and then apply several filters if provided."
  [unfiltered-db offensive-job-filter]
  (timers/time!
    rank-jobs-duration
    (try
      (->> (sort-jobs-by-dru-pool unfiltered-db)
           ;; Apply the offensive job filter first before taking.
           (pc/map-vals offensive-job-filter)
           (pc/map-vals #(map tools/job-ent->map %))
           (pc/map-vals #(remove nil? %)))
      (catch Throwable t
        (log/error t "Failed to rank jobs")
        (meters/mark! rank-jobs-failures)
        {}))))

(defn- start-jobs-prioritizer!
  [conn pool-name->pending-jobs-atom task-constraints trigger-chan]
  (let [offensive-jobs-ch (make-offensive-job-stifler conn)
        offensive-job-filter (partial filter-offensive-jobs task-constraints offensive-jobs-ch)]
    (tools/chime-at-ch trigger-chan
                       (fn rank-jobs-event []
                         (log/info "Starting pending job ranking")
                         (reset! pool-name->pending-jobs-atom
                                 (rank-jobs (d/db conn) offensive-job-filter))
                         (log/info "Done with pending job ranking")))))

(meters/defmeter [cook-mesos scheduler mesos-error])
(meters/defmeter [cook-mesos scheduler offer-chan-full-error])

(defn make-fenzo-state
  "Make a fenzo scheduler state. This consists of the fenzo scheduler itself and a set of tasks that
  should be unassigned the next iteration through."
  [offer-incubate-time-ms fitness-calculator good-enough-fitness]
  {:unassign-task-set (atom #{})
   :fenzo
   (.. (TaskScheduler$Builder.)
       (disableShortfallEvaluation) ;; We're not using the autoscaling features
       (withLeaseOfferExpirySecs (max (-> offer-incubate-time-ms time/millis time/in-seconds) 1)) ;; should be at least 1 second
       (withRejectAllExpiredOffers)
       (withFitnessCalculator (config/fitness-calculator fitness-calculator))
       (withFitnessGoodEnoughFunction (reify Func1
                                        (call [_ fitness]
                                          (> fitness good-enough-fitness))))
       (withLeaseRejectAction (reify Action1
                                (call [_ lease]
                                  (let [offer (:offer lease)
                                        id (:id offer)]
                                    (log/debug "Fenzo is declining offer" offer)
                                    (try
                                      (decline-offers (:compute-cluster offer) [id])
                                      (catch Exception e
                                        (log/error e "Unable to decline fenzos rejected offers")))))))
       (build))})

(defn persist-mea-culpa-failure-limit!
  "The Datomic transactor needs to be able to access this part of the
  configuration, so on cook startup we transact the configured value into Datomic."
  [conn limits]
  (when (map? limits)
    (let [default (:default limits)
          overrides (mapv (fn [[reason limit]] {:db/id [:reason/name reason]
                                                :reason/failure-limit limit})
                          (dissoc limits :default))]
      (when default
        @(d/transact conn [{:db/id :scheduler/config
                            :scheduler.config/mea-culpa-failure-limit default}]))
      (when (seq overrides)
        @(d/transact conn overrides))))
  (when (number? limits)
    @(d/transact conn [{:db/id :scheduler/config
                        :scheduler.config/mea-culpa-failure-limit limits}])))

(defn decline-offers-safe
  "Declines a collection of offers, catching exceptions"
  [compute-cluster offers]
  (try
    (decline-offers compute-cluster (map :id offers))
    (catch Exception e
      (log/error e "Unable to decline offers!"))))

(defn receive-offers
  [offers-chan match-trigger-chan compute-cluster pool-name offers]
  (doseq [offer offers]
    (histograms/update! (histograms/histogram (metric-title "offer-size-cpus" pool-name)) (get-in offer [:resources :cpus] 0))
    (histograms/update! (histograms/histogram (metric-title "offer-size-mem" pool-name)) (get-in offer [:resources :mem] 0)))
  (if (async/offer! offers-chan offers)
    (do
      (counters/inc! offer-chan-depth)
      (async/offer! match-trigger-chan :trigger)) ; :trigger is arbitrary, the value is ignored
    (do (log/warn "Offer chan is full. Are we not handling offers fast enough?")
        (meters/mark! offer-chan-full-error)
        (future
          (decline-offers-safe compute-cluster offers)))))

(let [in-order-queue-counter (counters/counter ["cook-mesos" "scheduler" "in-order-queue-size"])
      in-order-queue-timer (timers/timer ["cook-mesos" "scheduler" "in-order-queue-delay-duration"])
      parallelism 19 ; a prime number to potentially help make the distribution uniform
      processor-agents (->> #(agent nil)
                            (repeatedly parallelism)
                            vec)
      safe-call (fn agent-processor [_ body-fn]
                  (try
                    (body-fn)
                    (catch Exception e
                      (log/error e "Error processing mesos status/message."))))]
  (defn async-in-order-processing
    "Asynchronously processes the body-fn by queueing the task in an agent to ensure in-order processing."
    [order-id body-fn]
    (counters/inc! in-order-queue-counter)
    (let [timer-context (timers/start in-order-queue-timer)
          processor-agent (->> (mod (hash order-id) parallelism)
                               (nth processor-agents))]
      (send processor-agent safe-call
            #(do
               (timers/stop timer-context)
               (counters/dec! in-order-queue-counter)
               (body-fn))))))

(defn prepare-match-trigger-chan
  "Calculate the required interval for match-trigger-chan and start the chimes"
  [match-trigger-chan pools]
  (let [{:keys [global-min-match-interval-millis target-per-pool-match-interval-millis]} (config/offer-matching)
        match-interval-millis (-> target-per-pool-match-interval-millis
                                  (/ (count pools))
                                  int
                                  (max global-min-match-interval-millis))]
    (when (= match-interval-millis global-min-match-interval-millis)
      (log/warn "match-trigger-chan is set to the global minimum interval of " global-min-match-interval-millis " ms. "
                "This is a sign that we have more pools, " (count pools) " than we expect to have and we will "
                "schedule each pool less often than the desired setting of every " target-per-pool-match-interval-millis " ms."))
    (async/pipe (chime-ch (util/time-seq (time/now) (time/millis match-interval-millis))) match-trigger-chan)))

(defn create-datomic-scheduler
  [{:keys [conn cluster-name->compute-cluster-atom fenzo-fitness-calculator fenzo-floor-iterations-before-reset
           fenzo-floor-iterations-before-warn fenzo-max-jobs-considered fenzo-scaleback good-enough-fitness
           mea-culpa-failure-limit mesos-run-as-user agent-attributes-cache offer-incubate-time-ms
           pool-name->pending-jobs-atom rebalancer-reservation-atom task-constraints
           trigger-chans]}]

  (persist-mea-culpa-failure-limit! conn mea-culpa-failure-limit)

  (let [{:keys [match-trigger-chan rank-trigger-chan]} trigger-chans
        pools (->> conn d/db pool/all-pools (filter pool/schedules-jobs?))
        pools' (if (-> pools count pos?)
                 pools
                 [{:pool/name "no-pool"}])
        pool-name->fenzo-state (pool-map pools' (fn [_] (make-fenzo-state offer-incubate-time-ms
                                                                          fenzo-fitness-calculator good-enough-fitness)))
        pool->match-trigger-chan (pool-map pools' (fn [_] (async/chan (async/sliding-buffer 1))))
        pool-names-linked-list (LinkedList. (map :pool/name pools'))
        job->acceptable-compute-clusters-fn
        (if-let [fn-name-from-config
                 (:job->acceptable-compute-clusters-fn (config/kubernetes))]
          (util/lazy-load-var fn-name-from-config)
          job->acceptable-compute-clusters)
        pool->resources-atom (pool-map
                               pools'
                               (fn [{:keys [pool/name]}]
                                 (make-offer-handler
                                   conn (get pool-name->fenzo-state name)
                                   pool-name->pending-jobs-atom agent-attributes-cache
                                   fenzo-max-jobs-considered fenzo-scaleback fenzo-floor-iterations-before-warn
                                   fenzo-floor-iterations-before-reset (get pool->match-trigger-chan name)
                                   rebalancer-reservation-atom mesos-run-as-user name
                                   cluster-name->compute-cluster-atom job->acceptable-compute-clusters-fn)))]
    (prepare-match-trigger-chan match-trigger-chan pools')
    (async/go-loop []
      (when-let [x (async/<! match-trigger-chan)]
        (try
          (let [pool-name (.removeFirst pool-names-linked-list)]
            (.addLast pool-names-linked-list pool-name)
            (async/offer! (get pool->match-trigger-chan pool-name) x))
          (catch Exception e
            (log/error e "Exception in match-trigger-chan chime handler")
            (throw e)))
        (recur)))
    (log/info "Pool name to fenzo scheduler keys:" (keys pool-name->fenzo-state))
    (start-jobs-prioritizer! conn pool-name->pending-jobs-atom task-constraints rank-trigger-chan)
    {:pool-name->fenzo-state pool-name->fenzo-state
     :view-incubating-offers (fn get-resources-atom [p]
                               (deref (get pool->resources-atom p)))}))
