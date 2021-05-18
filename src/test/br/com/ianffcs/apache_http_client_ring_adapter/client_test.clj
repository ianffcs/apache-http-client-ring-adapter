(ns br.com.ianffcs.apache-http-client-ring-adapter.client-test
  (:require
   [br.com.ianffcs.apache-http-client-ring-adapter.client :refer [->http-client]]
   [clj-http.client :as client]
   [clojure.test :refer [deftest is testing]]
   [clojure.data.json :as json]
   [ring.core.protocols :as ring.protocols])
  (:import [java.io ByteArrayInputStream]))

(defn clj-http-mocked-req [mocked-client req]
  (-> req
      (assoc :http-client mocked-client)
      client/request
      (dissoc :request-time)))

(deftest ->http-client-test-clj-http
  (testing "simple get"
    (let [mocked-client (->http-client (fn [req]
                                         (prn req)
                                         {:body    "1337"
                                          :headers {"hello" "world"}
                                          :status  202}))]
      (is (= {:cached                nil,
              :repeatable?           false,
              :protocol-version      {:name "HTTP", :major 1, :minor 1},
              :streaming?            false,
              :http-client           mocked-client
              :chunked?              false,
              :reason-phrase         "Accepted",
              :headers               {"hello" "world"},
              :orig-content-encoding nil,
              :status                202,
              :length                4,
              :body                  "1337",
              :trace-redirects       []}
             (clj-http-mocked-req
               mocked-client
               {:method :get
                :url    "https://test.com.br/foo/bar"})))))

  (testing "simple post request"
    (let [*body         (promise)
          *request      (promise)
          mocked-client (->http-client (fn [request]
                                         (deliver *body (-> request :body))
                                         (deliver *request (dissoc request :body))
                                         {:body    (slurp @*body)
                                          :headers {"hello" "world"}
                                          :status  200}))]
      (is (= {:body                  {:msg "hello body"}
              :cached                nil
              :chunked?              false
              :headers               {"hello" "world"}
              :http-client mocked-client
              :length                20
              :orig-content-encoding nil
              :protocol-version      {:major 1
                                      :minor 1
                                      :name  "HTTP"}
              :reason-phrase         "OK"
              :repeatable?           false
              :status                200
              :streaming?            false
              :trace-redirects       []}
             (-> (clj-http-mocked-req
                   mocked-client
                   {:method      :post
                    :url         "https://test.com.br/foo/bar"
                    :headers     {"hello" "header"}
                    :body        (-> {:msg "hello body"}
                                     json/write-str
                                     .getBytes
                                     ByteArrayInputStream.)})
                 (update :body json/read-str :key-fn keyword)))))))

(deftest check-ring-spec-keys
  (let [*req (promise)
        http-client (->http-client (fn [req]
                                     (deliver *req (dissoc req :body))
                                     {:status 202}))]
    (client/request {:method      :post
                     :url         "https://example.com/bar?car=33"
                     :body        "{\"Hello\": 42}"
                     :headers     {"Hello" "World"}
                     :http-client http-client})
    (is (= {:request-method :post,
            :uri            "/bar"
            :scheme         :https
            :protocol       "HTTP/1.1"
            :query-string   "car=33"
            :server-port    -1
            :server-name    "example.com"
            ;; :remote-addr ""
            :headers        {"Connection"      "close"
                             "Hello"           "World"
                             "Accept-Encoding" "gzip, deflate"}}
           @*req))))


(deftest check-ring-spec-response-body
  (let [*req (promise)
        http-client (->http-client (fn [req]
                                     (deliver *req (dissoc req :body))
                                     {:body   (reify ring.protocols/StreamableResponseBody
                                                (write-body-to-stream [this response output-stream]
                                                  (.write output-stream (.getBytes "Hello!"))
                                                  (.close output-stream)))
                                      :status 202}))]
    (is (= "Hello!"
           (:body (client/request {:method      :post
                                   :url         "https://example.com/bar?car=33"
                                   :body        "{\"Hello\": 42}"
                                   :headers     {"Hello" "World"}
                                   :http-client http-client}))))))
