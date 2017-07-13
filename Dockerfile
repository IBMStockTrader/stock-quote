FROM websphere-liberty:microProfile
COPY server.xml /config/server.xml
COPY target/stock-quote-1.0-SNAPSHOT.war /config/apps/StockQuote.war
