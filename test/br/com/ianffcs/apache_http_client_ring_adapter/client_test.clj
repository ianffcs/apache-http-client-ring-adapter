(ns br.com.ianffcs.apache-http-client-ring-adapter.client-test
  (:require
    [br.com.ianffcs.apache-http-client-ring-adapter.client :refer [->http-client]]
    [clj-http.client :as client]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.test :refer [deftest is testing]]
    [ring.core.protocols :as ring.protocols])
  (:import (org.apache.http HttpResponse)))

(defn clj-http-mocked-req [mocked-client req]
  (-> req
      (assoc :http-client mocked-client)
      client/request
      (dissoc :request-time :http-client)))

(deftest ->http-client-test-clj-http-test
  (testing "simple get"
    (let [mocked-client (->http-client {:ring-handler (fn [_req]
                                                        {:body    "1337"
                                                         :headers {"hello" "world"}
                                                         :status  202})})]
      (is (= {:body                  "1337"
              :cached                nil
              :chunked?              false
              :headers               {"hello" "world"}
              :length                4
              :orig-content-encoding nil
              :protocol-version      {:name "HTTP", :major 1, :minor 1}
              :reason-phrase         "Accepted"
              :repeatable?           false
              :status                202
              :streaming?            false
              :trace-redirects       []}
             (clj-http-mocked-req
               mocked-client
               {:method :get
                :url    "https://test.com.br/foo/bar"})))))

  (testing "simple post request"
    (let [*body (promise)
          *request (promise)
          mocked-client (->http-client {:ring-handler (fn [request]
                                                        (deliver *body (-> request :body))
                                                        (deliver *request (dissoc request :body))
                                                        {:body    (slurp @*body)
                                                         :headers {"hello" "world"}
                                                         :status  200})})]
      (is (= {:body                  {:msg "hello body"}
              :cached                nil
              :chunked?              false
              :headers               {"hello" "world"}
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
                   {:method  :post
                    :url     "https://test.com.br/foo/bar"
                    :headers {"hello" "header"}
                    :body    (-> {:msg "hello body"}
                                 json/write-str
                                 .getBytes
                                 io/input-stream)})
                 (update :body json/read-str :key-fn keyword)))))))

(deftest check-ring-spec-keys-test
  (let [*req (promise)
        http-client (->http-client {:ring-handler (fn [req]
                                                    (deliver *req (dissoc req :body))
                                                    {:status 202})})]
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
            :server-name    "example.com"
            :headers        {"connection"      "close"
                             "hello"           "World"
                             "accept-encoding" "gzip, deflate"}}
           @*req))))


(deftest check-ring-spec-response-body-test
  (let [*req (promise)
        http-client (->http-client {:ring-handler (fn [req]
                                                    (deliver *req (dissoc req :body))
                                                    {:body   (reify ring.protocols/StreamableResponseBody
                                                               (write-body-to-stream [_this _response output-stream]
                                                                 (.write output-stream (.getBytes "Hello!"))
                                                                 (.close output-stream)))
                                                     :status 200})})]
    (is (= "Hello!"
           (:body (client/request {:method      :post
                                   :url         "https://example.com/bar?car=33"
                                   :body        "{\"Hello\": 42}"
                                   :headers     {"Hello" "World"}
                                   :http-client http-client}))))))



(deftest double-header-test
  (let [*headers (promise)
        http-client (->http-client {:ring-handler (fn [{:keys [headers]}]
                                                    (deliver *headers headers)
                                                    {:headers {"Hey" ["a" "b"]}
                                                     :status  202})})]
    (is (= {"Hey" ["a" "b"]}
           (:headers (client/request {:method      :get
                                      :url         "https://example.com/bar?car=33"
                                      :headers     {"Hello" ["World" "x"]}
                                      :http-client http-client}))))
    (is (= {"accept-encoding" "gzip, deflate"
            "connection"      "close"
            "hello"           "World,x"}
           @*headers))))

(defn check-token
  [http-client token]
  (let [response (client/get (str "https://api.example.com/check-token?token=" token)
                             {:http-client http-client
                              :as          :json})]
    (get-in response [:body :is_valid])))

(deftest check-token-example-test
  (let [mock-api-handler (fn [{:keys [query-string]}]
                           (if-let [token (second (re-find #"token=([a-z]+)"
                                                           query-string))]
                             {:body    (json/write-str {:is_valid (string/includes? token "b")})
                              :headers {"Content-Type" "application/json"}
                              :status  200}
                             ;; missing query
                             {:status 400}))
        http-client (->http-client {:ring-handler mock-api-handler})]
    (is (true? (check-token http-client "abc")))
    (is (false? (check-token http-client "efd")))
    (is (= "clj-http: status 400"
           (try
             (check-token http-client "123")
             (catch Throwable ex
               (ex-message ex)))))))

(deftest retry-execute-simple-test
  (let [counter (atom 0)
        max-retries 4
        http-client (->http-client
                      {:retry-interval 0
                       :max-retries max-retries
                       :retry-fn     (fn retry-fn
                                       [^HttpResponse resp cnt _ctx]
                                       (let [resp-status (-> resp
                                                             .getStatusLine
                                                             .getStatusCode)]
                                         (if (and (= 404 resp-status)
                                                  (> max-retries cnt))
                                           (do (swap! counter inc)
                                               true)
                                           false)))
                       :ring-handler (fn [_req]
                                       {:status 404
                                        :body   "Can't find"})})]
    (is (= "clj-http: status 404" (try (client/request {:method      :get
                                                        :url         "https://example.com/bar?car=33"
                                                        :http-client http-client})
                                       (catch Throwable t
                                         (ex-message t)))))
    (is (= (dec max-retries) @counter))))
