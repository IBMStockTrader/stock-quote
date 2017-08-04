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
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

//JSON-P (JSR 353).  The replaces my old usage of IBM's JSON4J (com.ibm.json.java.JSONObject)
import javax.json.Json;
import javax.json.JsonArray;
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
/** This version of StockQuote talks directly to Quandl.com */
public class StockQuote extends Application {
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
				System.out.println(quote.get("price"));
			} else {
				System.out.println("Usage: StockQuote <symbol>");
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
			t.printStackTrace();
		}
	}

    @GET
    @Path("/{symbol}")
	@Produces("application/json")
	/*  Getting stock quote directly from Quandl (no dependency on API Connect). */
	public JsonObject getStockQuote(@PathParam("symbol") String symbol, @QueryParam("key") String key) throws IOException {
    	if ((symbol==null) || symbol.equalsIgnoreCase("test")) return getTestQuote(TEST_SYMBOL, TEST_PRICE);
 
//		String uri = "https://api.us.apiconnect.ibmcloud.com/jalcornusibmcom-dev/sb/stocks/"+symbol;
		String uri = "https://www.quandl.com/api/v3/datasets/WIKI/"+symbol+".json?rows=1";

	   	if (key == null) key = quandl_key; //only 50 invocations per IP address are allowed per day without an API key
		if ((key != null) && !key.equals("")) uri += "&api_key="+key;

		JsonObject quote = null;
		try {
			System.out.println("Connecting to Redis using URL: "+redis_url);				

			URI jedisURI = new URI(redis_url);
			Jedis jedis = new Jedis(jedisURI); //Connect to Redis

			System.out.println("Getting "+symbol+" from Redis");				
			String cachedValue = jedis.get(symbol); //Try to get it from Redis
			if (cachedValue == null) { //It wasn't in Redis
				System.out.println(symbol+" wasn't in Redis");
				quote = extractFromQuandl(invokeREST("GET", uri), symbol); //so go get it like we did before we'd ever heard of Redis
				jedis.set(symbol, quote.toString()); //Put it Redis so it's there next time we ask
			} else {
				System.out.println("Got this from Redis for "+symbol+": "+cachedValue);
				StringReader reader = new StringReader(cachedValue);
				quote = Json.createReader(reader).readObject(); //use what we got from Redis
				reader.close();

				if (isStale(quote)) {
					System.out.println(symbol+" in Redis was too stale");
					try {
						quote = extractFromQuandl(invokeREST("GET", uri), symbol); //so go get a less stale value
						jedis.set(symbol, quote.toString()); //Put it Redis so it's there next time we ask
					} catch (Throwable t) {
						System.out.println("Error getting fresh quote; using cached value instead");
						t.printStackTrace();
					}
				} else {
					System.out.println("Used "+symbol+" from Redis");				
				}
			}

			jedis.close(); //Release resource
		} catch (Throwable t) {
			t.printStackTrace();

			//something went wrong using Redis.  Fall back to the old-fashioned direct approach
			try {
			quote = extractFromQuandl(invokeREST("GET", uri), symbol);
			} catch (Throwable t2) {
				t2.printStackTrace();
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
		if (then.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) multiplier = 3; //a Friday quote is good till 4 PM Monday

		Calendar now = Calendar.getInstance(); //initializes to instant it was called
		long difference = now.getTimeInMillis() - then.getTimeInMillis();

		String symbol = quote.getString("symbol");
		System.out.println("Quote for "+symbol+" is "+difference/((double)HOUR_IN_MILLISECONDS)+" hours old");

		return (difference > multiplier*DAY_IN_MILLISECONDS); //cached quote over a day old (Quandl only returns previous business day's closing value)
    }

	private JsonObject getTestQuote(String symbol, double price) { //in case Quandl is down or we're rate limited
		Date now = new Date();
		String today = formatter.format(now);

		JsonObjectBuilder builder = Json.createObjectBuilder();
		builder.add("symbol", symbol);
		builder.add("date", today);
		builder.add("price", price);

		return builder.build();
	}

	/*  Pluck the desired values out of a deeply nested JSON structure.
	 * 
	 *  Example of data returned for https://www.quandl.com/api/v3/datasets/WIKI/IBM.json?rows=1
	 *
     * {"dataset":{"id":9775747,"dataset_code":"IBM","database_code":"WIKI","name":"International Business Machines Corp (IBM) Prices, Dividends, Splits and Trading Volume","description":"End of day open, high, low, close and volume, dividends and splits, and split/dividend adjusted open, high, low close and volume for International Business Machines Corporation (IBM). Ex-Dividend is non-zero on ex-dividend dates. Split Ratio is 1 on non-split dates. Adjusted prices are calculated per CRSP (www.crsp.com/products/documentation/crsp-calculations)\n\nThis data is in the public domain. You may copy, distribute, disseminate or include the data in other products for commercial and/or noncommercial purposes.\n\nThis data is part of Quandl's Wiki initiative to get financial data permanently into the public domain. Quandl relies on users like you to flag errors and provide data where data is wrong or missing. Get involved: connect@quandl.com\n","refreshed_at":"2016-04-26T21:47:46.316Z","newest_available_date":"2016-04-26","oldest_available_date":"1962-01-02","column_names":["Date","Open","High","Low","Close","Volume","Ex-Dividend","Split Ratio","Adj. Open","Adj. High","Adj. Low","Adj. Close","Adj. Volume"],"frequency":"daily","type":"Time Series","premium":false,"limit":1,"transform":null,"column_index":null,"start_date":"1962-01-02","end_date":"2016-04-26","data":[["2016-04-26",148.65,149.79,147.9,149.08,2974825.0,0.0,1.0,148.65,149.79,147.9,149.08,2974825.0]],"collapse":null,"order":"desc","database_id":4922}}
	 */
	private JsonObject extractFromQuandl(JsonObject obj, String symbol) {
		JsonObject dataset = (JsonObject) obj.get("dataset");
		JsonArray outerArray = (JsonArray) dataset.get("data");
		JsonArray array = (JsonArray) outerArray.get(0);

		JsonObjectBuilder builder = Json.createObjectBuilder();
		builder.add("symbol", symbol.toUpperCase());
		builder.add("date", array.get(0)); //date is the first element
		builder.add("price", array.get(4)); //day closing value is the fifth element

		return builder.build();
	}
	
	private static JsonObject invokeREST(String verb, String uri) throws IOException {
		URL url = new URL(uri);

		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(verb);
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setDoOutput(true);
		InputStream stream = conn.getInputStream();

//		JSONObject json = JSONObject.parse(stream); //JSON4J
		JsonObject json = Json.createReader(stream).readObject();

		stream.close();

		return json;
	}
}
