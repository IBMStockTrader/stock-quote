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

/** JSON-B POJO class representing a Quote JSON object */
public class Quote {
    private String symbol;
    private double price;
    private String date;
    private long time = 0; //marker for time of day not being set


    public Quote() { //default constructor
    }

    public Quote(String initialSymbol) { //primary key constructor
        setSymbol(initialSymbol);
    }

    public Quote(String initialSymbol, double initialPrice, String initialDate) {
        setSymbol(initialSymbol);
        setPrice(initialPrice);
        setDate(initialDate);
    }

    public Quote(String initialSymbol, double initialPrice, String initialDate, long initialTime) {
        setSymbol(initialSymbol);
        setPrice(initialPrice);
        setDate(initialDate);
        setTime(initialTime);
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String newSymbol) {
        symbol = newSymbol;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double newPrice) {
        price = newPrice;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String newDate) {
        date = newDate;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long newTime) {
        time = newTime;
    }

    public boolean equals(Object obj) {
        boolean isEqual = false;
        if ((obj != null) && (obj instanceof Quote)) isEqual = toString().equals(obj.toString());
        return isEqual;
   }

    public String toString() {
        return "{\"symbol\": \""+symbol+"\", \"price\": "+price+", \"date\": \""+date+"\", \"time\": "+time+"}";
    }
}
