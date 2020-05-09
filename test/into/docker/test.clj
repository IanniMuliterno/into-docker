(ns into.docker.test
  "Mock Docker client that can be modified for specific test cases."
  (:require [into.docker :as docker]
            [into.docker.tar :as tar]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]])
  (:import [java.io
            ByteArrayInputStream
            ByteArrayOutputStream]
           [java.nio
            ByteBuffer
            ByteOrder]))

;; ## Exec

(defn exec-stream-block
  "Create a block as it would be returned from a docker exec stream,
   prefixed as either stdout or stderr."
  ^bytes [stream data]
  (let [^bytes data (if (string? data)
                      (.getBytes ^String data)
                      data)
        len         (count data)]
    (-> (ByteBuffer/allocate (+ len 8))
        (.put (byte (case stream :stdout 0x01 :stderr 0x02)))
        (.position 4)
        (.order ByteOrder/BIG_ENDIAN)
        (.putInt len)
        (.put data)
        (.array))))

(defrecord MockExec [container output result]
  docker/DockerExec
  (exec-container [this]
    container)
  (exec-stream [this]
    (with-open [out (ByteArrayOutputStream.)]
      (doseq [[stream data] output]
        (.write out (exec-stream-block stream data)))
      (ByteArrayInputStream.
       (.toByteArray out))))
  (exec-result [this]
    result))

;; ## Container

(defrecord MockContainer [filesystem]
  docker/DockerContainer
  (run-container [this])
  (commit-container [this data])
  (cleanup-container [this])
  (stream-from-container [this path]
    (->> (for [[fs-path data] @filesystem
               :when (string/starts-with? fs-path (str path "/"))]
           {:source data
            :length (count data)
            :path   (let [f (io/file fs-path)
                          p (.getParentFile f)]
                      ;; To replicate the behaviour of the actual
                      ;; `ContainerArchive` call, we have to include the
                      ;; directory name in the entry.
                      (str (.getName p) "/" (.getName f)))})
         (tar/tar)
         (ByteArrayInputStream.)))
  (stream-into-container [this target-path tar-stream]
    (doseq [{:keys [source path]} (tar/untar-seq tar-stream)]
      (swap! filesystem
             assoc
             (.getPath (io/file target-path path))
             source)))
  (run-container-command [this data]
    (let [[path & args] (:cmd data)]
      (case path
        "mkdir"
        (->MockExec this [] {:exit 0})

        "cat"
        (->MockExec
         this
         [[:stdout (get @filesystem (first args))]]
         {:exit 0})

        (let [{:keys [output result]} (apply
                                       (get @filesystem path)
                                       this
                                       args)]
          (->MockExec this output result))))))

(defn container
  "Create a new mock container."
  []
  (->MockContainer (atom {})))

(defn add-file
  "Add a file to the container."
  [container path data]
  (swap! (:filesystem container) assoc path data)
  container)

;; ## Client

(defrecord MockClient [containers images]
  docker/DockerClient
  (pull-image [this image])
  (inspect-image [this image]
    (get images image))
  (container [this _ image]
    (get containers image)))

(defn client
  "Create a new mock client."
  []
  (->MockClient {} {}))

(defn add-container
  [client image container]
  (assoc-in client [:containers image] container))

(defn add-image
  [client image data]
  (assoc-in client [:images image] data))

;; ## Test the Utilities

(deftest t-exec-stream-block
  (let [data  "HELLO"
        len   (count data)
        bytes (.getBytes data)]
    (is (= (concat [1 0 0 0 0 0 0 len] bytes)
           (seq (exec-stream-block :stdout data))))
    (is (= (concat [2 0 0 0 0 0 0 len] bytes)
           (seq (exec-stream-block :stderr data))))))

(deftest t-exec-container
  (let [container (-> (container)
                      (add-file
                       "/into/exec"
                       (fn [container path]
                         (add-file container path (byte-array 0))
                         {:output [[:stderr "Working..."]
                                   [:stdout "OK."]]
                          :result {:exit 0}})))
        stdout-builder (StringBuilder.)
        stderr-builder (StringBuilder.)
        write (fn [{:keys [stream line]}]
                (-> (case stream :stderr stderr-builder, :stdout stdout-builder)
                    (.append line)))
        result (docker/exec-and-log
                container
                {:cmd ["/into/exec" "test"]}
                write)]
    (is (= "Working..." (.toString stderr-builder)))
    (is (= "OK." (.toString stdout-builder)))
    (is (zero? (:exit result)))
    (is (contains? @(:filesystem container) "test"))))

(deftest t-transfer-between-containers
  (let [data (.getBytes "HELLO")
        from (-> (container)
                 (add-file "/dir/a" data)
                 (add-file "/dir/b" data))
        to   (container)]
    (docker/transfer-between-containers from to "/dir" "/target")
    (is (= #{"/target/dir/a" "/target/dir/b"}
           (set (keys @(:filesystem to)))))))

(deftest t-read-container-file
  (let [data "DATA"
        container (-> (container)
                      (add-file "/dir/file" (.getBytes data)))]
    (is (= data
           (String.  (docker/read-file container "/dir/file"))))))