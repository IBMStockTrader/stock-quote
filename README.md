The stock-quote microservice gets the price of a specified stock.  It hits an API in API Connect, which drives a call to Quandl.com to get the actual data.

It responds to a GET /{symbol} REST request, where you pass in a stock ticker symbol, and it returns a JSON object containing that symbol, the price, and the date it was quoted.

For example, if you hit the http://localhost:9080/stock-quote/IBM URL, it would return {"symbol": "IBM", "price": 155.23, "date": "2016-06-27"}

This service uses Redis for caching.  When a quote is requested, it first checks to see if the answer is in the cache, and if so, whether the quote is less that 24 hours old (Quandl only returns the previous business day's closing price), and if so, just uses that.  Otherwise (or if any exceptions occur communicating with Redis), it drives the REST call to API Connect as usual, then adds it to Redis so it's there for next time.

The "Java for Redis", or "Jedis", library is used for communicating with Redis.  The jar file must be placed in the war file's WEB-INF/lib directory to be available on the classpath at runtime.
