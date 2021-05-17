# Apache Http Client Ring Adapter

* Should conform to these specs:
  https://github.com/ring-clojure/ring/blob/master/SPEC
  
## Examples of usage with clj-http

* in [tests](br/com/ianffcs/apache_http_client_ring_adapter/jetty_client_test.clj)

## Usage

* Add to your `deps.edn`
```clojure
br.com.ianffcs/apache-http-client-ring-adapter {:git/url "https://github.com/ianffcs/apache-http-client-ring-adapter.git"
                                                :sha     "4051ec25a88982ed2282f8572d110833a7b25ff5"}
```

* require `->http-client`
```clojure
(require '[br.com.ianffcs.apache-http-client-ring-adapter.jetty-client :refer [->http-client]])

(->http-client (fn [req]
                 {:status 200
                  :body "mocked response"}))
```