<!--
       Copyright 2017 IBM Corp All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

The *stock-quote* microservice gets the price of a specified stock.  It hits an API in **API Connect**,
which drives a call to 'Quandl.com' to get the actual data.

It responds to a `GET /{symbol}` REST request, where you pass in a stock ticker symbol, and it returns
a JSON object containing that *symbol*, the *price*, and the *date* it was quoted.

For example, if you hit the `http://localhost:9080/stock-quote/IBM` URL, it would return
`{"symbol": "IBM", "price": 155.23, "date": "2016-06-27"}`

This service uses **Redis** for caching.  When a quote is requested, it first checks to see if the
answer is in the cache, and if so, whether the quote is less that 24 hours old (Quandl only returns the
previous business day's closing price), and if so, just uses that.  Otherwise (or if any exceptions
occur communicating with Redis), it drives the REST call to **API Connect** as usual, then adds it to
**Redis** so it's there for next time.

The *Java for Redis*, or **Jedis**, library is used for communicating with **Redis**.
