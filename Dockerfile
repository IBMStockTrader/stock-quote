FROM websphere-liberty:microProfile
COPY server.xml /config/server.xml
COPY build/libs/stock-quote.war /config/apps/StockQuote.war
