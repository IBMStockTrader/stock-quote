/*
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
 */

package com.ibm.hybrid.cloud.sample.portfolio;

//Standard HTTP request classes.  Maybe replace these with use of JAX-RS 2.0 client package instead...
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//JSON-P (JSR 353).  The replaces my old usage of IBM's JSON4J (com.ibm.json.java.JSONObject)
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

//JAX-RS 2.0 (JSR 339)
import javax.ws.rs.core.Application;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.GET;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.Path;

//Jedis (Java for Redis)
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;


@ApplicationPath("/")
@Path("/")
/** This version of StockQuote talks to API Connect (which talks to api.iextrading.com) */
public class StockQuote extends Application {
	private static Logger logger = Logger.getLogger(StockQuote.class.getName());
	private static JedisPool jedisPool = null;

	private static final long MINUTE_IN_MILLISECONDS = 60000;
	private static final double ERROR = -1;
	private static final String TEST_SYMBOL = "TEST";
	private static final double TEST_PRICE = 123.45;

	private long cache_interval = 60; //default to 60 minutes
	private SimpleDateFormat formatter = null;

	public static void main(String[] args) {
		try {
			if (args.length > 0) {
				StockQuote stockQuote = new StockQuote();
				JsonObject quote = stockQuote.getStockQuote(args[0]);
//				double value = ((JsonNumber) quote.get("price")).doubleValue();
				logger.info(""+quote.get("price"));
			} else {
				logger.info("Usage: StockQuote <symbol>");
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	public StockQuote() {
		super();

		try {
			//The following variable should be set in a Kubernetes secret, and
			//made available to the app via a stanza in the deployment yaml

			//Example secret creation command: kubectl create secret generic redis
			//--from-literal=url=redis://x:JTkUgQ5BXo@cache-redis:6379
			//--from-literal=cache-interval=<minutes>

			/* Example deployment yaml stanza:
			spec:
			  containers:
			  - name: stock-quote
			    image: ibmstocktrader/stock-quote:latest
			    env:
			      - name: REDIS_URL
			        valueFrom:
			          secretKeyRef:
			            name: redis
			            key: url
			      - name: CACHE_INTERVAL
			        valueFrom:
			          secretKeyRef:
			            name: redis
			            key: cache-interval
			    ports:
			      - containerPort: 9080
			    imagePullPolicy: Always
			*/
			String redis_url = System.getenv("REDIS_URL");
			URI jedisURI = new URI(redis_url);
			logger.fine("Initializing Redis pool using URL: "+redis_url);
			jedisPool = new JedisPool(jedisURI);

			try {
				String cache_string = System.getenv("CACHE_INTERVAL");
				if (cache_string != null) {
					cache_interval = Long.parseLong(cache_string);
				}
			} catch (Throwable t) {
				logger.warning("No cache interval set - defaulting to 60 minutes");
			}
			formatter = new SimpleDateFormat("yyyy-MM-dd");
			logger.fine("Initialization complete!");
		} catch (Throwable t) {
			logException(t);
		}
	}

	@GET
	@Path("/")
	@Produces("application/json")
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	/**  Get all stock quotes in Redis */
	public JsonArray getAllCachedStocks() {
		JsonArrayBuilder stocks = Json.createArrayBuilder();

		if (jedisPool != null) try {
			Jedis jedis = jedisPool.getResource(); //Get a connection from the pool

			Set<String> keys = jedis.keys("*");
			Iterator<String> iter = keys.iterator();
			while (iter.hasNext()) {
				String key = iter.next();
				String cachedValue = jedis.get(key);
				logger.fine("Found this in Redis for "+key+": "+cachedValue);

				StringReader reader = new StringReader(cachedValue);
				JsonObject quote = Json.createReader(reader).readObject();
				reader.close();

				stocks.add(quote);
			}
		} catch (Throwable t) {
			logException(t);
		}
		return stocks.build();
	}

	@GET
	@Path("/{symbol}")
	@Produces("application/json")
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	/**  Get stock quote from API Connect */
	public JsonObject getStockQuote(@PathParam("symbol") String symbol) throws IOException {
		if (symbol.equalsIgnoreCase("test")) return getTestQuote(TEST_SYMBOL, TEST_PRICE);

		String uri = "https://api.us.apiconnect.ibmcloud.com/jalcornusibmcom-dev/sb/stocks/"+symbol.toUpperCase();
//		String uri = "https://api.iextrading.com/1.0/stock/"+symbol.toUpperCase()+"/quote";

		JsonObject quote = null;
		if (jedisPool != null) try {
			Jedis jedis = jedisPool.getResource(); //Get a connection from the pool
			if (jedis==null) logger.warning("Unable to get connection to Redis from pool");

			logger.fine("Getting "+symbol+" from Redis");
			String cachedValue = jedis.get(symbol); //Try to get it from Redis
			if (cachedValue == null) { //It wasn't in Redis
				logger.fine(symbol+" wasn't in Redis so we will try to put it there");
				quote = invokeREST("GET", uri); //so go get it like we did before we'd ever heard of Redis
				logger.fine("Got quote for "+symbol+" from API Connect");
				jedis.set(symbol, quote.toString()); //Put in Redis so it's there next time we ask
				logger.fine("Put "+symbol+" in Redis");
			} else {
				logger.fine("Got this from Redis for "+symbol+": "+cachedValue);
				StringReader reader = new StringReader(cachedValue);
				quote = Json.createReader(reader).readObject(); //use what we got from Redis
				reader.close();

				if (isStale(quote)) {
					logger.fine(symbol+" in Redis was too stale");
					try {
						quote = invokeREST("GET", uri); //so go get a less stale value
						logger.fine("Got quote for "+symbol+" from API Connect");
						jedis.set(symbol, quote.toString()); //Put in Redis so it's there next time we ask
						logger.info("Refreshed "+symbol+" in Redis");
					} catch (Throwable t) {
						logger.info("Error getting fresh quote; using cached value instead");
						logger.log(Level.WARNING, t.getClass().getName(), t);
					}
				} else {
					logger.info("Used "+symbol+" from Redis");
				}
			}

			logger.fine("Completed getting stock quote - releasing Redis resources");
			jedis.close(); //Release resource
		} catch (Throwable t) {
			logException(t);
			
			//something went wrong using Redis.  Fall back to the old-fashioned direct approach
			try {
				quote = invokeREST("GET", uri);
				logger.fine("Got quote for "+symbol+" from API Connect");
			} catch (Throwable t2) {
				logException(t2);
				return getTestQuote(symbol, ERROR);
			}
		} else {
			//Redis not configured.  Fall back to the old-fashioned direct approach
			try {
				logger.warning("Redis URL not configured, so driving call directly to API Connect");
				quote = invokeREST("GET", uri);
				logger.fine("Got quote for "+symbol+" from API Connect");
			} catch (Throwable t3) {
				logException(t3);
				return getTestQuote(symbol, ERROR);
			}
		}

		return quote;
	}

	private boolean isStale(JsonObject quote) {
		long now = System.currentTimeMillis();
		JsonNumber whenQuoted = (JsonNumber) quote.get("time");
		if (whenQuoted==null) return true;
		long then = whenQuoted.longValue();
		long difference = now - then;

		if (logger.isLoggable(Level.FINE)) {
			String symbol = quote.getString("symbol");
			logger.fine("Quote for "+symbol+" is "+difference/((double)MINUTE_IN_MILLISECONDS)+" minutes old");
		}

		return (difference > cache_interval*MINUTE_IN_MILLISECONDS); //cached quote is too old
    }

	private JsonObject getTestQuote(String symbol, double price) { //in case API Connect or IEX is down or we're rate limited
		Date now = new Date();
		String today = formatter.format(now);

		logger.fine("Building a hard-coded quote (bypassing Redis and API Connect");

		JsonObjectBuilder builder = Json.createObjectBuilder();
		builder.add("symbol", symbol);
		builder.add("date", today);
		builder.add("price", price);

		return builder.build();
	}

	private static JsonObject invokeREST(String verb, String uri) throws IOException {
		logger.fine("Building URL for REST service: "+uri);
		URL url = new URL(uri);

		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(verb);
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setDoOutput(true);

		logger.fine("Reading data from REST service");
		InputStream stream = conn.getInputStream();

		logger.fine("Parsing REST service response as JSON");
//		JSONObject json = JSONObject.parse(stream); //JSON4J
		JsonObject json = Json.createReader(stream).readObject();

		stream.close();

		logger.fine("Returning JSON from REST service");
		return json;
	}

	private static void logException(Throwable t) {
		logger.warning(t.getClass().getName()+": "+t.getMessage());

		//only log the stack trace if the level has been set to at least FINE
		if (logger.isLoggable(Level.INFO)) {
			StringWriter writer = new StringWriter();
			t.printStackTrace(new PrintWriter(writer));
			logger.info(writer.toString());
		}
	}
}
