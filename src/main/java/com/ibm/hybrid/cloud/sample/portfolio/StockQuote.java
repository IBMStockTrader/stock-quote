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
import java.net.URISyntaxException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.util.TimeZone;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//JSON-P (JSR 353).  The replaces my old usage of IBM's JSON4J (com.ibm.json.java.JSONObject)
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

//JAX-RS 2.0 (JSR 339)
import javax.ws.rs.core.Application;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.GET;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Path;

//Jedis (Java for Redis)
import redis.clients.jedis.Jedis;


@ApplicationPath("/")
@Path("/")
/** This version of StockQuote talks to API Connect (which talks to Quandl.com) */
public class StockQuote extends Application {
	private static Logger logger = Logger.getLogger(StockQuote.class.getName());
	private static final long HOUR_IN_MILLISECONDS = 60*60*1000;
	private static final long DAY_IN_MILLISECONDS = 24*HOUR_IN_MILLISECONDS;
	private static final double ERROR = -1;
	private static final String TEST_SYMBOL = "TEST";
	private static final double TEST_PRICE = 123.45;

	private String redis_url  = null;
	private String quandl_key = null;
	private SimpleDateFormat formatter = null;

	public static void main(String[] args) {
		try {
			if (args.length > 0) {
				String key = (args.length == 2) ? args[1] : null;
				StockQuote stockQuote = new StockQuote();
				JsonObject quote = stockQuote.getStockQuote(args[0], key);
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
			//--from-literal=url=redis://x:JTkUgQ5BXo@voting-moth-redis:6379
			//--from-literal=quandl-key=<your quandl.com key>

			/* Example deployment yaml stanza:
			spec:
			  containers:
			  - name: stock-quote
			    image: kyleschlosser/stock-quote:redis
			    env:
			      - name: REDIS_URL
			        valueFrom:
			          secretKeyRef:
			            name: redis
			            key: url
			      - name: QUANDL_KEY
			        valueFrom:
			          secretKeyRef:
			            name: redis
			            key: quandl-key
			    ports:
			      - containerPort: 9080
			    imagePullPolicy: Always
			    imagePullSecrets:
			     - name: dockerhubsecret
			*/
			redis_url = System.getenv("REDIS_URL");
			quandl_key = System.getenv("QUANDL_KEY");

			formatter = new SimpleDateFormat("yyyy-MM-dd");
		} catch (Throwable t) {
			logException(t);
		}
	}

	@GET
	@Path("/")
	@Produces("application/json")
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	/*  Get all stock quotes in Redis */
	public JsonArray getAllCachedStocks() {
		JsonArrayBuilder stocks = Json.createArrayBuilder();

		if (redis_url != null) try {
			URI jedisURI = new URI(redis_url);
			logger.fine("Connecting to Redis using URL: "+redis_url);
			Jedis jedis = new Jedis(jedisURI); //Connect to Redis

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
	/*  Get stock quote from API Connect */
	public JsonObject getStockQuote(@PathParam("symbol") String symbol, @QueryParam("key") String key) throws IOException {
		if (symbol.equalsIgnoreCase("test")) return getTestQuote(TEST_SYMBOL, TEST_PRICE);

		String uri = "https://api.us.apiconnect.ibmcloud.com/jalcornusibmcom-dev/sb/stocks/"+symbol.toUpperCase();
//		String uri = "https://www.quandl.com/api/v3/datasets/WIKI/"+symbol+".json?rows=1";

	   	if (key == null) key = quandl_key; //only 50 invocations per IP address are allowed per day without an API key

		if ((key != null) && !key.equals("")) uri += "?quandl_key="+key;

		JsonObject quote = null;
		if (redis_url != null) try {
			logger.fine("Connecting to Redis using URL: "+redis_url);

			URI jedisURI = new URI(redis_url);
			Jedis jedis = new Jedis(jedisURI); //Connect to Redis

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

	private boolean isStale(JsonObject quote) throws ParseException {
		String dateQuoted = quote.getString("date");
		Date date = formatter.parse(dateQuoted);

		Calendar then = Calendar.getInstance();
		then.setTime(date);
		then.setTimeZone(TimeZone.getTimeZone("EST5EDT")); //NYSE time zone
		then.set(Calendar.HOUR_OF_DAY, 16); //4 PM market close

		short multiplier = 1;
		if (then.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) {
			logger.fine("Cached quote is from Friday, so good till Monday");
			multiplier = 3; //a Friday quote is good till 4 PM Monday
		}

		Calendar now = Calendar.getInstance(); //initializes to instant it was called
		long difference = now.getTimeInMillis() - then.getTimeInMillis();

		String symbol = quote.getString("symbol");
		logger.fine("Quote for "+symbol+" is "+difference/((double)HOUR_IN_MILLISECONDS)+" hours old");

		return (difference > multiplier*DAY_IN_MILLISECONDS); //cached quote over a day old (Quandl only returns previous business day's closing value)
    }

	private JsonObject getTestQuote(String symbol, double price) { //in case Quandl is down or we're rate limited
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

		//only log the stack trace if the level has been set to at least INFO
		if (logger.isLoggable(Level.INFO)) {
			StringWriter writer = new StringWriter();
			t.printStackTrace(new PrintWriter(writer));
			logger.info(writer.toString());
		}
	}
}
