# Apache Http Client Ring Adapter

This project intends to facilitate the creation of `org.apache.http.client.CloseableHttpClient`, allowing things like

- Mock external requests made by clients like `clj-http`
- Testing your `ring` app via `clj-http`



* Should conform to these specs:
  https://github.com/ring-clojure/ring/blob/master/SPEC

# Usage

* Add to your `deps.edn`
```clojure
br.com.ianffcs/apache-http-client-ring-adapter {:git/url "https://github.com/ianffcs/apache-http-client-ring-adapter.git"
                                                :sha     "f576b007fc38283d754fc610808ec48aa528714f"}
```

* Require it
```clojure
(require '[br.com.ianffcs.apache-http-client-ring-adapter.client :refer [->http-client]])
```

## Mocking external requests

Let's suppose that you have an function that call's an external API via `clj-http`

You can easily test this function by passing an `http-client` for it.

In "production", you can call `(check-token nil "...")`. `clj-http` will use its default `http-client` in this case.

```clojure
#_(require '[clj-http.client :as client]
           '[clojure.test :refer [deftest is]]
           '[clojure.data.json :as json]
           '[clojure.string :as string]
           '[br.com.ianffcs.apache-http-client-ring-adapter.client :refer [->http-client]])

(defn check-token
  [http-client token]
  (let [response (client/get (str "https://api.example.com/check-token?token=" token)
                             {:http-client http-client
                              :as          :json})]
    (get-in response [:body :is_valid])))

(deftest check-token-example
  (let [mock-api-handler (fn [{:keys [query-string]}]
                           (if-let [token (second (re-find #"token=([a-z]+)"
                                                           query-string))]
                             {:body    (json/write-str {:is_valid (string/includes? token "b")})
                              :headers {"Content-Type" "application/json"}
                              :status  200}
                             ;; missing query
                             {:status 400}))
        http-client (->http-client mock-api-handler)]
    (is (true? (check-token http-client "abc")))
    (is (false? (check-token http-client "efd")))
    (is (= "clj-http: status 400"
           (try
             (check-token http-client "123")
             (catch Throwable ex
               (ex-message ex)))))))
```

## Testing your app via `clj-http`

If you develop an ring-app and start it like this:

```clojure
(defn -main []
  (run-server app-handler {... :port ...}))
```

Now you can test your app like this:

```clojure
(deftest api-test
  (let [http-client (->http-client app-handler)]
    (is (= 200
           (:status (client/get "https://app-handler/my-method" {:http-client http-client}))))))
```

Some things like the https scheme, port and hostname may not make sense in this case. So you can use any value

But in an ring server that listen many ports with many hosts, it will be a good idea.


## Others examples of usage with clj-http

* in [tests](https://github.com/ianffcs/apache-http-client-ring-adapter/blob/main/src/test/br/com/ianffcs/apache_http_client_ring_adapter/client_test.clj)

* require `->http-client`

```clojure
(require '[br.com.ianffcs.apache-http-client-ring-adapter.client :refer [->http-client]])

(->http-client (fn [req]
                 {:status 200
                  :body   "mocked response"}))
```
