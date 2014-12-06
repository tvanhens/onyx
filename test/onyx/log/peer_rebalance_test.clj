(ns onyx.log.peer-rebalance-test
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [com.stuartsierra.component :as component]
            [onyx.system :refer [onyx-development-env]]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.extensions :as extensions]
            [onyx.log.util :as util]
            [onyx.api :as api]
            [midje.sweet :refer :all]
            [zookeeper :as zk]))

(def onyx-id (java.util.UUID/randomUUID))

(def config (read-string (slurp (clojure.java.io/resource "test-config.edn"))))

(def dev (onyx-development-env onyx-id (:env config)))

(def env (component/start dev))

(def peer-opts
  {:inbox-capacity 1000
   :outbox-capacity 1000
   :job-scheduler :onyx.job-scheduler/round-robin
   :state {:task-lifecycle-fn util/stub-task-lifecycle}})

(def catalog-1
  [{:onyx/name :a
    :onyx/ident :hornetq/read-segments
    :onyx/type :input
    :onyx/medium :hornetq
    :onyx/consumption :concurrent}

   {:onyx/name :b
    :onyx/ident :hornetq/write-segments
    :onyx/type :output
    :onyx/medium :hornetq
    :onyx/consumption :concurrent}])

(def catalog-2
  [{:onyx/name :c
    :onyx/ident :hornetq/read-segments
    :onyx/type :input
    :onyx/medium :hornetq
    :onyx/consumption :concurrent}

   {:onyx/name :d
    :onyx/ident :hornetq/write-segments
    :onyx/type :output
    :onyx/medium :hornetq
    :onyx/consumption :concurrent}])

(def j1
  (onyx.api/submit-job (:log env)
                       {:workflow [[:a :b]]
                        :catalog catalog-1
                        :task-scheduler :onyx.task-scheduler/round-robin}))

(def j2
  (onyx.api/submit-job (:log env)
                       {:workflow [[:c :d]]
                        :catalog catalog-2
                        :task-scheduler :onyx.task-scheduler/round-robin}))

(def n-peers 12)

(def v-peers (onyx.api/start-peers! onyx-id n-peers (:peer config) peer-opts))

(def ch (chan n-peers))

(extensions/subscribe-to-log (:log env) 0 ch)

(def replica-1
  (loop [replica {:job-scheduler (:job-scheduler peer-opts)}]
    (let [position (<!! ch)
          entry (extensions/read-log-entry (:log env) position)
          new-replica (extensions/apply-log-entry entry replica)]
      (if-not (and (= (count (:a (get (:allocations new-replica) j1))) 3)
                   (= (count (:b (get (:allocations new-replica) j1))) 3)
                   (= (count (:c (get (:allocations new-replica) j2))) 3)
                   (= (count (:d (get (:allocations new-replica) j2))) 3))
        (recur new-replica)
        new-replica))))

(fact "the peers evenly balance" true => true)

(def conn (zk/connect (:zookeeper/address (:zookeeper (:env config)))))

(def id (last (:b (get (:allocations replica-1) j1))))

(zk/delete conn (str (onyx.log.zookeeper/pulse-path onyx-id) "/" id))

(zk/close conn)

(def replica-2
  (loop [replica replica-1]
    (let [position (<!! ch)
          entry (extensions/read-log-entry (:log env) position)
          new-replica (extensions/apply-log-entry entry replica)]
      (if-not (and (= (count (:a (get (:allocations new-replica) j1))) 3)
                   (= (count (:b (get (:allocations new-replica) j1))) 3)
                   (= (count (:c (get (:allocations new-replica) j2))) 3)
                   (= (count (:d (get (:allocations new-replica) j2))) 2))
        (recur new-replica)
        new-replica))))

(fact "the peers rebalance" true => true)

(doseq [v-peer v-peers]
  (try
    ((:shutdown-fn v-peer))
    (catch Exception e (prn e))))

(component/stop env)
