# Apache Http Client Ring Adapter

* Should conform to these specs:
  https://github.com/ring-clojure/ring/blob/master/SPEC
  
## Examples of usage with clj-http

* in [tests](br/com/ianffcs/apache_http_client_ring_adapter/client_test.clj)

## Usage

* Add to your `deps.edn`
```clojure
br.com.ianffcs/apache-http-client-ring-adapter {:git/url "https://github.com/ianffcs/apache-http-client-ring-adapter.git"
                                                :sha     "f576b007fc38283d754fc610808ec48aa528714f"}
```

* require `->http-client`

```clojure
(require '[br.com.ianffcs.apache-http-client-ring-adapter.client :refer [->http-client]])

(->http-client (fn [req]
                 {:status 200
                  :body   "mocked response"}))
```
