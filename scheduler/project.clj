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
(defproject cook "1.59.5-SNAPSHOT"
  :description "This launches jobs on a Mesos cluster with fair sharing and preemption"
  :license {:name "Apache License, Version 2.0"}
  :dependencies [[org.clojure/clojure "1.10.3"]

                 ;;Data marshalling
                 [org.clojure/data.codec "0.1.0"]
                 ^:displace [cheshire "5.3.1"]
                 [byte-streams "0.1.4"]
                 [org.clojure/data.json "0.2.2"]
                 [camel-snake-kebab "0.4.0"]
                 [com.rpl/specter "1.0.1"]

                 ;;Utility
                 [com.google.guava/guava "17.0"]
                 [amalloy/ring-buffer "1.1"]
                 [listora/ring-congestion "0.1.2"]
                 [lonocloud/synthread "1.0.4"]
                 [org.clojure/tools.namespace "0.2.4"]
                 [org.clojure/core.cache "0.8.2"]
                 [org.clojure/core.memoize "0.5.8"]
                 [clj-time "0.12.0"]
                 [org.clojure/core.async "0.3.442" :exclusions [org.clojure/tools.reader]]
                 [org.clojure/tools.cli "0.3.5"]
                 [prismatic/schema "1.1.3"]
                 [clojure-miniprofiler "0.4.0"]
                 [jarohen/chime "0.1.6"]
                 [org.clojure/data.priority-map "0.0.5"]
                 [swiss-arrows "1.0.0"]
                 [riddley "0.1.10"]
                 ^:displace [com.netflix.fenzo/fenzo-core "0.10.0"
                             :exclusions [org.apache.mesos/mesos
                                          com.fasterxml.jackson.core/jackson-core
                                          org.slf4j/slf4j-api
                                          org.slf4j/slf4j-simple]]
                 [better-cond "1.0.1"]

                 ;;Logging
                 [org.clojure/tools.logging "0.2.6"]
                 [clj-logging-config "1.9.10"
                  :exclusions [log4j]]
                 [org.slf4j/slf4j-log4j12 "1.7.12"]
                 [com.draines/postal "1.11.0"
                  :exclusions [commons-codec]]
                 [prismatic/plumbing "0.5.3"]
                 [log4j "1.2.17"]
                 [log4j/apache-log4j-extras "1.2.17"]
                 [instaparse "1.4.0"]
                 [org.codehaus.jsr166-mirror/jsr166y "1.7.0"]
                 [clj-pid "0.1.1"]
                 [jarohen/chime "0.1.6"]

                 ;;Networking
                 [twosigma/clj-http "2.0.0-ts1"]
                 [cc.qbits/jet "0.6.4" :exclusions [org.eclipse.jetty/jetty-io
                                                    org.eclipse.jetty/jetty-security
                                                    org.eclipse.jetty/jetty-server
                                                    org.eclipse.jetty/jetty-http
                                                    cheshire]]
                 [org.eclipse.jetty/jetty-server "9.2.6.v20141205"]
                 [org.eclipse.jetty/jetty-security "9.2.6.v20141205"]

                 ; TODO: Upgrade netty.
                 ; Bring in netty. Note that this is brought in in a lot of other places, Exclude it whenever finding another one.
                 ; Need to make sure all imports are importing the same version.
                 ;[io.netty/netty-handler "4.1.63.Final"]
                 ; Netty now uses epoll with a native library. We need to tell lein our architecture so it can bring in the
                 ; right library architecture. Make sure this version matches.
                 ;[io.netty/netty-transport-native-epoll "4.1.63.Final" :classifier "linux-x86_64"]
                 ;[io.netty/netty-transport-native-unix-common "4.1.63.Final" :classifier "linux-x86_64"]
                 [io.netty/netty "3.10.1.Final"]


                 ;;Metrics

                 ; Metrics-clojure-jvm 2.10.0 depends on io.dropwizard.metrics 3.x, but
                 ; io.dropwizard.metrics/metrics-jvm 3.x is broken on JDK > 8 because it makes an illegal access.
                 ; metrics-clojure-jvm has had a fix for this -- updated dependencies -- since 2019 (in the unreleased 3.x)
                 ; So we bring in 2.10.0 but force a later version of io.dropwizard.

                 [io.dropwizard.metrics/metrics-graphite "4.1.21"]
                 [io.dropwizard.metrics/metrics-core "4.1.21"]
                 [io.dropwizard.metrics/metrics-jvm "4.1.21"]
                 [io.dropwizard.metrics/metrics-jmx "4.1.21"]
                 [metrics-clojure "2.10.0"
                  :exclusions [io.netty/netty org.clojure/clojure]]
                 ; We want to include jvm metrics, but can't. Between dropwizard 3.x and 4.x, JvmAttributeGaugeSet moved
                 ; packages metrics-clojure-jvm 3.x has the new location, but isn't released. So keep this disabled for now.
                 ;[metrics-clojure-jvm "2.10.0"]; :exclusions [io.dropwizard.metrics/metrics-jvm]]
                 [metrics-clojure-graphite "2.10.0"]

                 [metrics-clojure-ring "2.3.0" :exclusions [com.codahale.metrics/metrics-core
                                                            org.clojure/clojure io.netty/netty]]
                 ;;External system integrations
                 [org.clojure/tools.nrepl "0.2.3"]

                 ;;Ring
                 [ring/ring-core "1.4.0"]
                 [ring/ring-devel "1.4.0" :exclusions [org.clojure/tools.namespace]]
                 [compojure "1.4.0"]
                 [metosin/compojure-api "1.1.8"]
                 [hiccup "1.0.5"]
                 [ring/ring-json "0.2.0"]
                 [ring-edn "0.1.0"]
                 [com.duelinmarkers/ring-request-logging "0.2.0"]
                 [liberator "0.15.0"]

                 ;;Databases
                 ;; TODO: Should upgrade curator to avoid illegal reflective access warnings. (5.2.0 is latest)
                 [org.apache.curator/curator-framework "2.7.1"
                  :exclusions [io.netty/netty]]
                 [org.apache.curator/curator-recipes "2.7.1"
                  :exclusions [org.slf4j/slf4j-log4j12
                               org.slf4j/log4j
                               log4j]]
                 ; Used when doing local testing in open source to setup mock curator.
                 [org.apache.curator/curator-test "2.7.1"
                 :exclusions [io.netty/netty
                              io.netty/netty-transport-native-epoll]]
                 [com.mchange/c3p0 "0.9.5.5"] ; Connection pooling.
                 [com.github.seancorfield/next.jdbc "1.2.761"]

                 ;; Dependency management
                 [mount "0.1.12"]

                 ;; Kubernetes

                 ;; We exclude okhttp3 from the kubernetes-client and force it to be a later version
                 ;; for less buggy http2 support. As of okhttp 4.9.2, still buggy, but only buggy in production!
                 ;; As a short-term mitigation, we're forcing http 1.1. (see make-api-client)
                 ;; At some point when okhttp matures, we should test http 2.
                 ;; Test removing these lines with client-java > 13.
                 [io.kubernetes/client-java "11.0.2" :exclusions [com.squareup.okhttp3/okhttp com.squareup.okhttp3/logging-interceptor]]
                 [com.squareup.okhttp3/okhttp "4.9.2"]
                 [com.squareup.okhttp3/logging-interceptor "4.9.2"]
                 [com.google.auth/google-auth-library-oauth2-http "0.16.2"]

                 ;Version forcing by JDK11 upgrade.
                 [org.flatland/ordered "1.5.9"] ; Upgraded from 1.5.3, brought in by clj-yaml.
                 [jakarta.xml.ws/jakarta.xml.ws-api "2.3.3"] ; Needed via liberator, No longer in JDK11.
                 [com.taoensso/encore "3.19.0"] ; Used by ring-congestion. 
                 ]

  :repositories {"maven2" {:url "https://files.couchbase.com/maven2/"}
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}

  :filespecs [{:type :fn
               :fn (fn [_]
                     {:type :bytes
                      :path "git-log"
                      :bytes (.trim (:out (clojure.java.shell/sh
                                            "git" "rev-parse" "HEAD")))})}
              {:type :fn
               :fn (fn [{:keys [version]}]
                     {:type :bytes
                      :path "version"
                      :bytes version})}]

  :java-source-paths ["./java"]

  :profiles
  {; By default, activate the :oss profile (explained below)
   :default [:base :system :user :provided :dev :oss]

   ; The :oss profile exists so that Cook can be built with a more
   ; appropriate set of dependencies for a specific environment than
   ; the ones defined here (by using `lein with-profile -oss` ...)
   :oss
   {:dependencies [
                   ; For example, one could drop in the datomic-pro
                   ; library instead of the datomic-free library, by
                   ; using a profiles.clj file that defines a profile
                   ; which pulls in datomic-pro
                   [com.datomic/datomic-free "0.9.5206" ; Note that updating this causes activeMQ errors. Suspect related to OpenSSL.
                    :exclusions [io.netty/netty
                                 com.fasterxml.jackson.core/jackson-core
                                 joda-time
                                 org.slf4j/jcl-over-slf4j
                                 org.slf4j/jul-to-slf4j
                                 org.slf4j/log4j-over-slf4j
                                 org.slf4j/slf4j-api
                                 org.slf4j/slf4j-nop
                                 com.amazonaws/aws-java-sdk]]
                   ; Similarly, one could use an older version of the
                   ; mesomatic library in environments that require it
                   [twosigma/mesomatic "1.5.0-r4"]
                   ; Opensource JDBC
                   [org.postgresql/postgresql "42.2.18"] ; Use PG 13.2 features.
                   ]}

   :uberjar
   {:aot [cook.components]
    :dependencies [[com.datomic/datomic-free "0.9.5206" ; Note that updating this causes activeMQ errors. Suspect related to OpenSSL.
                    :exclusions [com.fasterxml.jackson.core/jackson-core
                                 joda-time
                                 org.slf4j/jcl-over-slf4j
                                 org.slf4j/jul-to-slf4j
                                 org.slf4j/log4j-over-slf4j
                                 org.slf4j/slf4j-api
                                 org.slf4j/slf4j-nop
                                 com.amazonaws/aws-java-sdk]]]} ; aws brings in a lot of dependencies.

   :dev
   {:dependencies [[criterium "0.4.4"]
                   [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                      javax.jms/jms
                                                      com.sun.jdmk/jmxtools
                                                      com.sun.jmx/jmxri]]
                   [ring/ring-jetty-adapter "1.5.0"]]
    :jvm-opts ["-Xms2G"
               "-XX:-OmitStackTraceInFastThrow"
               "-Xmx2G"
               "-Dcom.sun.management.jmxremote.authenticate=false"
               "-Dcom.sun.management.jmxremote.ssl=false"]
    :resource-paths ["test-resources"]
    :source-paths []}

   :test
   {:dependencies [[criterium "0.4.4"]
                   [org.clojure/test.check "0.6.1"]
                   [org.mockito/mockito-core "3.12.4"]
                   [twosigma/cook-jobclient "0.8.8-SNAPSHOT"]]}

   :test-console
   [:test {:jvm-opts ["-Dcook.test.logging.console"]}]

   :override-maven {:local-repo ~(System/getenv "COOK_SCHEDULER_MAVEN_LOCAL_REPO")}

   :docker
   ; avoid calling javac in docker
   ; (.java sources are only used for unit test support)
   {:java-source-paths ^:replace []}}

  :plugins [[lein-exec "0.3.7"]
            [lein-print "0.1.0"]]

  :test-selectors {:all (constantly true)
                   :all-but-benchmark (complement :benchmark)
                   :benchmark :benchmark
                   :default (complement #(or (:integration %) (:benchmark %)))
                   :integration :integration}

  :main cook.components
  :jvm-opts ["-Dpython.cachedir.skip=true"
             ;"-Dsun.security.jgss.native=true"
             ;"-Dsun.security.jgss.lib=/opt/mitkrb5/lib/libgssapi_krb5.so"
             ;"-Djavax.security.auth.useSubjectCredsOnly=false"
             "-Xlog:gc*,compaction*,stringdedup*=debug,stringtable*,ergo*,safepoint,gc+age=trace:file=gclog-%t:time,level,tags"
             "-XX:+UseG1GC"
             "-XX:+UseStringDeduplication"
             "-XX:+HeapDumpOnOutOfMemoryError"
             "--illegal-access=warn"
	     ])
