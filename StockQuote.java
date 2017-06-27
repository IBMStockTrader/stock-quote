package com.ibm.hybrid.cloud.sample.portfolio;

//Standard HTTP request classes.  Maybe replace these with use of JAX-RS 2.0 client package instead...
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.ParseException;
//import java.text.SimpleDateFormat;
//import java.util.Date;

//JSON-P (JSR 353).  The replaces my old usage of IBM's JSON4J (com.ibm.json.java.JSONObject)
import javax.json.Json;
import javax.json.JsonObject;

//JAX-RS 2.0 (JSR 339)
import javax.ws.rs.core.Application;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.GET;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Path;

//Jedis (Java library for Redis)
import redis.clients.jedis.Jedis; //needs jedis-2.9.0.jar on the classpath


@ApplicationPath("/")
@Path("/")
/** This version of StockQuote talks to API Connect (which talks to Quandl.com) */
public class StockQuote extends Application {
//	private static final long DAY_IN_MILLISECONDS = 24*60*60*1000;

//	private static final String REDIS_URL = "redis://x:DQJIQGKUYGBCNKTN@sl-us-dal-9-portal.1.dblayer.com:15146"; //Kyle's Redis
	private static final String REDIS_URL = "redis://x:JTkUgQ5BXo@voting-moth-redis:6379"; //Greg's Redis in CFC

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

    @GET
    @Path("/{symbol}")
	@Produces("application/json")
	/*  Get the stock quote from the API defined in API Connect.  Behind the scenes,
	 *  that API's implementation delegates to Quandl.com.
	 * 
	 *  Example of data returned for https://api.us.apiconnect.ibmcloud.com/jalcornusibmcom-dev/sb/stocks/
	 *
     * { "symbol": "IBM", "date": "2017-01-03", "price": 167.19 }
	 */
	public JsonObject getStockQuote(@PathParam("symbol") String symbol, @QueryParam("key") String key) throws IOException, URISyntaxException {
		String uri = "https://api.us.apiconnect.ibmcloud.com/jalcornusibmcom-dev/sb/stocks/"+symbol;
		if (key != null) uri += "&quandl_key="+key; //only a few invocations per IP address are allowed per day without a key

		JsonObject quote = null;

		try {
			System.out.println("Connecting to Redis using URL: "+REDIS_URL);				

			URI jedisURI = new URI(REDIS_URL);
			Jedis jedis = new Jedis(jedisURI); //Connect to Redis

			System.out.println("Getting "+symbol+" from Redis");				
			String cachedValue = jedis.get(symbol); //Try to get it from Redis
			if (cachedValue == null) { //It wasn't in Redis
				System.out.println(symbol+" wasn't in Redis");
				quote = invokeREST("GET", uri); //so go get it like we did before we'd ever heard of Redis
				jedis.set(symbol, quote.toString()); //Put it Redis so it's there next time we ask
			} else {
				System.out.println("Got this from Redis for "+symbol+": "+cachedValue);
				StringReader reader = new StringReader(cachedValue);
				quote = Json.createReader(reader).readObject(); //use what we got from Redis
				reader.close();

				if (isStale(quote)) {
					System.out.println(symbol+" in Redis was too stale");
					quote = invokeREST("GET", uri); //so go get a less stale value
					jedis.set(symbol, quote.toString()); //Put it Redis so it's there next time we ask
				} else {
					System.out.println("Used "+symbol+" from Redis");				
				}
			}

			jedis.close(); //Release resource
		} catch (Throwable t) {
			t.printStackTrace();

			//something went wrong using Redis.  Fall back to the old-fashioned direct approach
			quote = invokeREST("GET", uri);
		}

		return quote;
	}

//	@SuppressWarnings("deprecation")
	private boolean isStale(JsonObject quote) throws ParseException {
/*
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
		String dateQuoted = quote.getString("date");
		Date then = format.parse(dateQuoted);
		then.setHours(16); //4 PM market close

		Date today = new Date();
		long difference = today.getTime() - then.getTime();

		return (difference > DAY_IN_MILLISECONDS); //cached quote over a day old (Quandl only returns previous day's closing value)
*/
    	return false;
    }

	private JsonObject invokeREST(String verb, String uri) throws IOException {
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
