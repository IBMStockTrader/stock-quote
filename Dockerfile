FROM websphere-liberty:microProfile
COPY server.xml /config/server.xml
COPY StockQuote.war /config/apps/StockQuote.war
