(ns br.com.ianffcs.apache-http-client-ring-adapter.jetty-client-test
  (:require
   [br.com.ianffcs.apache-http-client-ring-adapter.jetty-client :refer [->http-client]]
   [clj-http.client :as client]
   [clojure.test :refer [deftest is testing]]
   [clojure.data.json :as json])
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
                                         {:body    @*body
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
