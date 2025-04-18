/*
       Copyright 2017-2022 IBM Corp All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.stockquote;

import com.ibm.hybrid.cloud.sample.stocktrader.stockquote.client.APIConnectClient;
import com.ibm.hybrid.cloud.sample.stocktrader.stockquote.client.IEXClient;
import com.ibm.hybrid.cloud.sample.stocktrader.stockquote.json.Quote;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//JSON-B (JSR 367).  This largely replaces the need for JSON-P
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;

//JAX-RS 2.0 (JSR 339)
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

//CDI 1.2
import javax.inject.Inject;
import javax.enterprise.context.RequestScoped;

//mpRestClient 1.0
import org.eclipse.microprofile.rest.client.inject.RestClient;

//mpFaultTolerance 1.1
import org.eclipse.microprofile.faulttolerance.Fallback;

//Jedis (Java for Redis)
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;


@ApplicationPath("/")
@Path("/")
@RequestScoped
/** This version of StockQuote talks to API Connect (which talks to api.iextrading.com).
  * Note that more recently, we've been using serverless functions (Lambda in AWS, or 
  * Azure Cloud Functions), instead of IBM's API Connect, as the facade around IEX, though
  * we've been maintaing the same OpenAPI contract as was required by API Connect. */
public class StockQuote extends Application {
	private static Logger logger = Logger.getLogger(StockQuote.class.getName());
	private static JedisPool jedisPool = null;
	private static JedisPoolConfig jedisPoolConfig = null;

	private static final long MINUTE_IN_MILLISECONDS = 60000;
	private static final double ERROR       = -1;
	private static final String FAIL_SYMBOL = "FAIL";
	private static final String SLOW_SYMBOL = "SLOW";
	private static final long   SLOW_TIME   = 60000; //one minute
	private static final String TEST_SYMBOL = "TEST";
	private static final double TEST_PRICE  = 123.45;

	private static long cache_interval = 60; //default to 60 minutes
	private static boolean initializationFailed = false;
	private static SimpleDateFormat formatter = null;
	private static String iexApiKey = null;
	private static HashMap<String, Quote> backupCache = null; //in case Redis is unavailable, don't use up all our monthly calls to IEX

	private @Inject @RestClient APIConnectClient apiConnectClient;
	private @Inject @RestClient IEXClient iexClient;

	// Override API Connect Client URL if secret is configured to provide URL
	static {
		String mpUrlPropName = APIConnectClient.class.getName() + "/mp-rest/url";
		String urlFromEnv = System.getenv("APIC_URL");
		if ((urlFromEnv != null) && !urlFromEnv.isEmpty()) {
			logger.info("Using API Connect URL from config map: " + urlFromEnv);
			System.setProperty(mpUrlPropName, urlFromEnv);
		} else {
			logger.info("API Connect URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
		}

		mpUrlPropName = IEXClient.class.getName() + "/mp-rest/url";
		urlFromEnv = System.getenv("IEX_URL");
		if ((urlFromEnv != null) && !urlFromEnv.isEmpty()) {
			logger.info("Using IEX URL from config map: " + urlFromEnv);
			System.setProperty(mpUrlPropName, urlFromEnv);
		} else {
			logger.info("IEX URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
		}

		iexApiKey = System.getenv("IEX_API_KEY");
		if ((iexApiKey == null) || iexApiKey.isEmpty()) {
			logger.warning("No API key provided for IEX.  If API Connect isn't available, fallback to direct calls to IEX will fail");
		}
	}

	public static void main(String[] args) {
		try {
			if (args.length > 0) {
				StockQuote stockQuote = new StockQuote();
				Quote quote = stockQuote.getStockQuote(args[0]);
				logger.info("$"+quote.getPrice());
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

			if ((jedisPool == null) && !initializationFailed) try { //the pool is static; the connections within the pool are obtained as needed
				String redis_url = System.getenv("REDIS_URL");
				URI jedisURI = new URI(redis_url);
				logger.info("Initializing Redis pool using URL: "+redis_url);
				// @rtclauss Add connection pool configuration to combat potentially stale connections
				jedisPoolConfig = getPoolConfig();

				jedisPool = new JedisPool(jedisPoolConfig, jedisURI);
				if (jedisPool != null) logger.info("Redis pool initialized successfully!");
			} catch (Throwable t) {
				initializationFailed = true; //so we don't retry the above thousands of times and log big stack traces each time
				logException(t);
			}

			//this is in a separate if block because the above Jedis stuff will throw an exception if not properly configured
			if (backupCache == null) {
				backupCache = new HashMap<String, Quote>();
				formatter = new SimpleDateFormat("yyyy-MM-dd");

				try {
					String cache_string = System.getenv("CACHE_INTERVAL");
					if (cache_string != null) {
						cache_interval = Long.parseLong(cache_string);
					}
				} catch (Throwable t) {
					logger.warning("No cache interval set - defaulting to 60 minutes");
				}
				logger.info("Initialization complete!");
			}
		} catch (Throwable t) {
			logException(t);
		}
	}

	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	/**  Get all stock quotes in Redis.  This is a read-only operation that just returns what's already there, without any refreshing */
	public List<Quote> getAllCachedQuotes() {
		ArrayList<Quote> quotes = new ArrayList<>();

		if (jedisPool != null) {
			// @rtclauss try-with-resources to release the jedis instance back to the pool when done
			try (Jedis jedis = jedisPool.getResource();){ //Get a connection from the pool
				logger.finest("getAllCachedQuotes " + getPoolCurrentUsage());

				Set<String> keys = jedis.keys("*");
				Iterator<String> iter = keys.iterator();
				while (iter.hasNext()) {
					String key = iter.next();
					String cachedValue = jedis.get(key);
					logger.fine("Found this in Redis for "+key+": "+cachedValue);

					Jsonb jsonb = JsonbBuilder.create();
					Quote quote = jsonb.fromJson(cachedValue, Quote.class);

					quotes.add(quote);
				}
			} catch (Throwable t) {
				logException(t);
			}
		} else {
			logger.warning("jedisPool is null in getAllCachedQuotes()");
		}
		return quotes;
	}

	@POST
	@Path("/{symbol}")
	@Produces(MediaType.APPLICATION_JSON)
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	/**  Set stock quote into cache.  Call this if IEX is failing, to load the backup cache with some stock prices */
	public void updateCache(@PathParam("symbol") String symbol, @QueryParam("price") double price) throws IOException {
		logger.fine("Updating backupCache for "+symbol);
		Quote quote = getTestQuote(symbol, price);
		backupCache.put(symbol, quote);
	}

	@GET
	@Path("/{symbol}")
	@Produces(MediaType.APPLICATION_JSON)
	@Fallback(fallbackMethod = "getStockQuoteViaIEX")
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	/**  Get stock quote from API Connect */
	public Quote getStockQuote(@PathParam("symbol") String symbol) throws IOException {
		if (symbol.equalsIgnoreCase(TEST_SYMBOL)) return getTestQuote(TEST_SYMBOL, TEST_PRICE);
		if (symbol.equalsIgnoreCase(SLOW_SYMBOL)) return getSlowQuote();
		if (symbol.equalsIgnoreCase(FAIL_SYMBOL)) { //to help test Istio retry policies
			logger.info("Throwing a RuntimeException for symbol FAIL!");
			throw new RuntimeException("Failing as requested, since you asked for FAIL!");
		}

		Quote quote = null;
		// @rtclauss try-with-resources to release the jedis instance back to the pool when done
		if (jedisPool != null) try (Jedis jedis = jedisPool.getResource();) { //Get a connection from the pool
			//following getPoolCurrentUsage() call is kind of expensive, so only do it if the logging level is cranked to FINEST
			if (logger.isLoggable(Level.FINEST)) logger.finest("getStockQuote " + getPoolCurrentUsage());
			String cachedValue = null;

			if (jedis==null) {
				logger.warning("Unable to get connection to Redis from pool");
			} else {
				logger.fine("Getting "+symbol+" from Redis");
				cachedValue = jedis.get(symbol); //Try to get it from Redis
			}
			if (cachedValue == null) { //It wasn't in Redis
				logger.fine(symbol+" wasn't in Redis so we will try to put it there");
				quote = apiConnectClient.getStockQuoteViaAPIConnect(symbol); //so go get it like we did before we'd ever heard of Redis
				logger.fine("Got quote for "+symbol+" from API Connect");
				if (jedis != null) {
					jedis.set(symbol, quote.toString()); //Put in Redis so it's there next time we ask
					logger.fine("Put "+symbol+" in Redis");
				}
			} else {
				logger.fine("Got this from Redis for "+symbol+": "+cachedValue);

				try {
					Jsonb jsonb = JsonbBuilder.create();
					quote = jsonb.fromJson(cachedValue, Quote.class);
				} catch (Throwable t4) {
					logger.info("Unable to parse JSON obtained from Redis for "+symbol+".  Proceeding as if the quote was too stale.");
					logException(t4);
				}

				if (isStale(quote)) {
					logger.info(symbol+" in Redis was too stale");
					try {
						quote = apiConnectClient.getStockQuoteViaAPIConnect(symbol); //so go get a less stale value
						if (quote != null) {
							logger.fine("Got fresh quote for "+symbol+" from API Connect");
							quote.setTime(System.currentTimeMillis()); //so we don't report stale after the market has closed or on weekends
							jedis.set(symbol, quote.toString()); //Put in Redis so it's there next time we ask
							backupCache.put(symbol, quote);
							logger.fine("Refreshed "+symbol+" in Redis");
						} else {
							logger.warning("Got null from the stock quote provider");
						}
					} catch (Throwable t5) {
						logger.info("Error getting fresh quote; using cached value instead");
						logException(t5);
					}
				} else {
					logger.fine("Used "+symbol+" from Redis");
				}
			}

			logger.fine("Completed getting stock quote - releasing Redis resources automatically");
		} catch (Throwable t) {
			logException(t);

		} else {
			//Redis not configured.  Fall back to the old-fashioned direct approach
			logger.info("Redis not available, so resorting to using a per-pod static HashMap for caching.  Bounce pod to refresh the static backup cache");
		}
	
		if (quote == null) { //give up on Redis and do it the old fashioned way
			logger.warning("Something went wrong getting the quote.  Falling back to non-Redis approach, with the backup cache");
			quote = backupCache.get(symbol);
			if (quote != null) {
				logger.fine(symbol+" found in backup cache");
			} else try { //don't bother with cache staleness if Redis isn't configured (bounce pod to get fresh)
				logger.fine(symbol+" not found in backup cache, so driving call directly to API Connect");
				quote = apiConnectClient.getStockQuoteViaAPIConnect(symbol);
				logger.fine("Got quote for "+symbol+" from API Connect - adding to the backup cache");
				backupCache.put(symbol, quote);
			} catch (Throwable t3) {
				logException(t3);
				return getTestQuote(symbol, ERROR);
			}
		}

		return quote;
	}

	/** When API Connect is unavailable, fall back to calling IEX directly to get the stock quote */
	public Quote getStockQuoteViaIEX(String symbol) throws IOException {
		logger.info("Using fallback method getStockQuoteViaIEX");
		Quote quote = backupCache.get(symbol);
		if (quote != null) {
			logger.fine(symbol+" found in backup cache");
		} else try { //don't bother with cache staleness if API Connect isn't configured (bounce pod to get fresh)
			logger.fine(symbol+" not found in backup cache, so driving call directly to IEX");
			quote = iexClient.getStockQuoteViaIEX(symbol, iexApiKey);
			logger.fine("Got quote for "+symbol+" from IEX - adding to the backup cache");
			backupCache.put(symbol, quote);
		} catch (Throwable t) {
			logException(t);
			return getTestQuote(symbol, ERROR);
		}
		return quote;
	}

	private boolean isStale(Quote quote) {
		if (quote==null) return true;

		long now = System.currentTimeMillis();
		long then = quote.getTime();

		if (then==0) return true; //no time value present in quote
		long difference = now - then;

		String symbol = quote.getSymbol();
		logger.fine("Quote for "+symbol+" is "+difference/((double)MINUTE_IN_MILLISECONDS)+" minutes old");

		return (difference > cache_interval*MINUTE_IN_MILLISECONDS); //cached quote is too old
	}

	private Quote getTestQuote(String symbol, double price) { //in case API Connect or IEX is down or we're rate limited
		Date now = new Date();
		String today = formatter.format(now);

		logger.info("Building a hard-coded quote (bypassing Redis and API Connect");

		Quote quote = new Quote(symbol, price, today);

		logger.info("Returning hard-coded quote: "+quote!=null ? quote.toString() : "null");

		return quote;
	}

	private Quote getSlowQuote() { //to help test Istio timeout policies; deliberately not put in Redis cache
		logger.info("Sleeping for one minute for symbol SLOW!");

		try {
			Thread.sleep(SLOW_TIME); //to help test Istio timeout policies
		} catch (Throwable t) {
			logException(t);
		}

		logger.info("Done sleeping.");

		return getTestQuote(SLOW_SYMBOL, TEST_PRICE);
	}

	public static JedisPoolConfig getPoolConfig() {
		if (jedisPoolConfig == null) {
			JedisPoolConfig poolConfig = new JedisPoolConfig();

			// Each thread trying to access Redis needs its own Jedis instance from the pool.
			// Using too small a value here can lead to performance problems, too big and you have wasted resources.
			int maxConnections = 200;
			poolConfig.setMaxTotal(maxConnections);
			poolConfig.setMaxIdle(maxConnections);

			// This controls the number of connections that should be maintained for bursts of load.
			// Increase this value when you see pool.getResource() taking a long time to complete under burst scenarios
			poolConfig.setMinIdle(50);

			// Using "false" here will make it easier to debug when your maxTotal/minIdle/etc settings need adjusting.
			// Setting it to "true" will result better behavior when unexpected load hits in production
			poolConfig.setBlockWhenExhausted(true);

			// How long to wait before throwing when pool is exhausted
			poolConfig.setMaxWait(Duration.ofSeconds(30));

			// Test the connection before it's about to be reused
			poolConfig.setTestOnBorrow(true);

			// Test the pool when we're idle
			poolConfig.setTestWhileIdle(true);

			// Set eviction timeout for idle connections
			poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(60));

			//Amount of time to wait before evicting idle connections
			poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

			// Check all the connections at once
			poolConfig.setNumTestsPerEvictionRun(3); // test all connections

			StockQuote.jedisPoolConfig = poolConfig;
		}

		return jedisPoolConfig;
	}

	public static String getPoolCurrentUsage() {
		JedisPool jedisPool = StockQuote.jedisPool;
		JedisPoolConfig poolConfig = getPoolConfig();

		int active = jedisPool.getNumActive();
		int idle = jedisPool.getNumIdle();
		int total = active + idle;
		String log = String.format(
				"JedisPool: Active=%d, Idle=%d, Waiters=%d, total=%d, maxTotal=%d, minIdle=%d, maxIdle=%d",
				active,
				idle,
				jedisPool.getNumWaiters(),
				total,
				poolConfig.getMaxTotal(),
				poolConfig.getMinIdle(),
				poolConfig.getMaxIdle()
		);

		return log;
	}

	private static void logException(Throwable t) {
		logger.warning(t.getClass().getName()+": "+t.getMessage());

		//only log the stack trace if the level has been set to at least the specified level
		if (logger.isLoggable(Level.INFO)) {
			StringWriter writer = new StringWriter();
			t.printStackTrace(new PrintWriter(writer));
			logger.info(writer.toString());
		}
	}
}
