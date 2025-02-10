(ns br.com.ianffcs.apache-http-client-ring-adapter.client
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.core.protocols])
  (:import (java.io ByteArrayOutputStream)
           (java.net ConnectException URI UnknownHostException)
           (org.apache.http Header HeaderIterator HttpEntity HttpHost HttpRequest HttpResponse StatusLine)
           (org.apache.http.client ServiceUnavailableRetryStrategy)
           (org.apache.http.client.methods CloseableHttpResponse
                                           HttpEntityEnclosingRequestBase)
           (org.apache.http.impl EnglishReasonPhraseCatalog)
           (org.apache.http.impl.client CloseableHttpClient HttpClients)
           (org.apache.http.protocol BasicHttpContext HttpContext)))

(set! *warn-on-reflection* true)

(defn ->ring-request
  [{:keys [method
           ^URI request-uri
           port
           ^HttpHost target
           protocol
           headers
           http-request]}]
  (merge
    {}
    (when method
      {:request-method method})
    (when-let [uri (.getPath request-uri)]
      {:uri uri})
    (when (pos? port)
      {:server-port port})
    (when-let [query-string (.getQuery request-uri)]
      {:query-string query-string})
    (when-let [server-name (.getHost request-uri)]
      {:server-name server-name})
    (when-let [scheme (some-> target .getSchemeName keyword)]
      {:scheme scheme})
    (when-let [remote-addr (.getAddress target)]
      {:remote-addr (str remote-addr)})
    (when protocol
      {:protocol (str protocol)})
    (when (seq headers)
      {:headers headers})
    (when (instance? HttpEntityEnclosingRequestBase http-request)
      (some->> ^HttpEntityEnclosingRequestBase http-request
               .getEntity
               .getContent
               (hash-map :body)))))

(defn ->http-response
  [{:keys [body-as-bytes
           status
           protocol
           headers]}]
  (reify CloseableHttpResponse
    (close [_this])
    (getEntity [_this]
      (reify HttpEntity
        (getContent [_this]
          (io/input-stream body-as-bytes))
        (getContentLength [_this]
          (count body-as-bytes))
        (isRepeatable [_this]
          false)
        (isStreaming [_this]
          false)
        (isChunked [_this]
          false)))
    (getStatusLine [_this]
      (reify StatusLine
        (getReasonPhrase [_this] (.getReason EnglishReasonPhraseCatalog/INSTANCE status nil))
        (getStatusCode [_this] status)
        (getProtocolVersion [_this] protocol)))
    (headerIterator [_this]
      (let [headers (atom (for [[k vs] headers
                                v (if (coll? vs)
                                    vs
                                    [vs])]
                            (reify Header
                              (getName [_this] k)
                              (getValue [_this] (str v)))))]
        (reify HeaderIterator
          (hasNext [_this] (not (empty? @headers)))
          (next [_this]
            (ffirst (swap-vals! headers rest))))))))

(defn ->service-unavailable-retry-strategy
  [{:keys [retry-fn
           retry-interval]}]
  (reify ServiceUnavailableRetryStrategy
    (retryRequest
      [_this
       response
       retry-count
       context]
      (retry-fn response
                retry-count
                (BasicHttpContext. context)))
    (getRetryInterval
      [_this]
      (or retry-interval 500))))

(defn ->http-client
  [{:keys [ring-handler
           retry-fn
           max-retries]
    :or {max-retries 3}
    :as   http-mock-map}]
  (let [^ServiceUnavailableRetryStrategy
        retry-strategy (when retry-fn
                         (->service-unavailable-retry-strategy http-mock-map))
        http-client-executed (proxy [CloseableHttpClient] []
                               (getConnectionManager [_] nil)
                               (close [_])
                               (doExecute [^HttpHost target
                                           ^HttpRequest http-request
                                           ^HttpContext context]
                                 (let [request-line (.getRequestLine http-request)
                                       method (-> request-line
                                                  .getMethod
                                                  string/lower-case
                                                  keyword)
                                       request-uri (some-> request-line
                                                           .getUri
                                                           URI/create)
                                       protocol (.getProtocolVersion request-line)
                                       port (.getPort target)
                                       headers (reduce (fn [headers
                                                            ^Header header]
                                                         (update headers
                                                                 (string/lower-case (.getName header))
                                                                 (fn [v]
                                                                   (str (when v
                                                                          (str v ","))
                                                                        (.getValue header)))))
                                                       {}
                                                       (.getAllHeaders http-request))
                                       ring-req-map {:method       method
                                                     :http-request http-request
                                                     :request-uri  request-uri
                                                     :target       target
                                                     :protocol     protocol
                                                     :headers      headers
                                                     :port         port}
                                       {:keys [headers
                                               status
                                               body]
                                        :as   response} (-> ring-req-map
                                                            ->ring-request
                                                            ring-handler)
                                       body-as-bytes (-> (ByteArrayOutputStream.)
                                                         (doto
                                                           (->> (ring.core.protocols/write-body-to-stream body response))
                                                           .flush)
                                                         .toByteArray)]
                                   (if retry-strategy
                                     (loop [retry-count 0
                                            last-response nil]
                                       (let [response (if (zero? retry-count)
                                                        (-> ring-req-map
                                                            ->ring-request
                                                            ring-handler)
                                                        last-response)
                                             {:keys [status]} response]
                                         (if (and (< retry-count max-retries)
                                                  (.retryRequest retry-strategy
                                                                 (->http-response {:body-as-bytes body-as-bytes
                                                                                   :status        status
                                                                                   :protocol      protocol
                                                                                   :headers       headers})
                                                                 retry-count
                                                                 context))
                                           (do (Thread/sleep ^long (.getRetryInterval retry-strategy))
                                               (recur (inc retry-count) response)))))
                                     (->http-response {:body-as-bytes body-as-bytes
                                                       :status        status
                                                       :protocol      protocol
                                                       :target        target
                                                       :headers       headers})))))]
    (if retry-strategy
      (-> (HttpClients/custom)
          (.setServiceUnavailableRetryStrategy retry-strategy)
          (.build))
      http-client-executed)))

(defn unknown-host-exception
  "When can't resolve the host in DNS"
  [{:keys [server-name]}]
  (UnknownHostException. server-name))


(defn offline-exception
  "When can't resolve the name because it's offline"
  [{:keys [server-name]}]
  (UnknownHostException. (str server-name ": Temporary failure in name resolution")))

(defn connect-exception
  "When java can't open a socket in the address"
  [_]
  (ConnectException. "Connection refused"))


#_(comment
    (-> '[clj-http.client :refer [request]]
        (require '[clojure.test :refer [deftest is testing]]))

    (deftest ->http-client-test
             (testing "simple get"
                      (let [mocked-client (->http-client (fn [req]
                                                           {:body    "ian"
                                                            :headers {"hello" "world"}
                                                            :status  202}))]
                        (is (= {:cached                nil,
                                :request-time          1,
                                :repeatable?           false,
                                :protocol-version      {:name "HTTP", :major 1, :minor 1},
                                :streaming?            false,
                                :http-client           mocked-client
                                :chunked?              false,
                                :reason-phrase         "Accepted",
                                :headers               {"hello" "world"},
                                :orig-content-encoding nil,
                                :status                202,
                                :length                3,
                                :body                  "ian",
                                :trace-redirects       []}
                               (request {:url         "https://souenzzo.com.br/foo/bar"
                                         :method      :get
                                         :http-client mocked-client})))))
             (testing "simple get"
                      (let [mocked-client (->http-client (fn [req]
                                                           (def _r req)
                                                           {:body    "ian"
                                                            :headers {"hello" "world"}
                                                            :status  202}))]
                        (is (= {:cached                nil,
                                :request-time          1,
                                :repeatable?           false,
                                :protocol-version      {:name "HTTP", :major 1, :minor 1},
                                :streaming?            false,
                                :http-client           mocked-client
                                :chunked?              false,
                                :reason-phrase         "Accepted",
                                :headers               {"hello" "world"},
                                :orig-content-encoding nil,
                                :status                202,
                                :length                3,
                                :body                  "ian",
                                :trace-redirects       []}
                               (request {:url         "https://souenzzo.com.br/foo/bar"
                                         :method      :post
                                         :body        {}
                                         :http-client mocked-client})))))))



#_#_{:keys [authority port host scheme]} (-> url URI/create bean)
