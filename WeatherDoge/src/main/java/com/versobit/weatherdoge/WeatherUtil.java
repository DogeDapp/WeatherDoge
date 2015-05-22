/*
 * Copyright (C) 2014-2015 VersoBit Ltd
 *
 * This file is part of Weather Doge.
 *
 * Weather Doge is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Weather Doge is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Weather Doge.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.versobit.weatherdoge;

import android.annotation.SuppressLint;
import android.util.Log;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.json.JSONObject;

import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class WeatherUtil {

    private static final String TAG = WeatherUtil.class.getSimpleName();

    private static final Pattern YAHOO_TIME = Pattern.compile("([0-9]{1,2}):([0-9]{2}) (am|pm)");
    @SuppressLint("SimpleDateFormat")
    private static final SimpleDateFormat YAHOO_DATE_FORMAT = new SimpleDateFormat("EEE, d MMM yyyy h:m a zzz");

    private WeatherUtil() {
        //
    }

    // Fetch weather from a general location search string. Source dependent. Your mileage may vary.
    static WeatherResult getWeather(String location, Source source) {
        return getWeather(Double.MIN_VALUE, Double.MIN_VALUE, location, source);
    }

    // Fetch weather from geographic coordinates
    static WeatherResult getWeather(double latitude, double longitude, Source source) {
        return getWeather(latitude, longitude, null, source);
    }

    private static WeatherResult getWeather(double latitude, double longitude, String location, Source source) {
        switch (source) {
            case OPEN_WEATHER_MAP:
                return getWeatherFromOWM(latitude, longitude, location);
            case YAHOO:
                return getWeatherFromYahoo(latitude, longitude, location);
        }
        throw new IllegalArgumentException("No supported weather source provided.");
    }

    private static WeatherResult getWeatherFromYahoo(double latitude, double longitude, String location) {
        try {
            String yqlText;
            if(latitude == Double.MIN_VALUE && longitude == Double.MIN_VALUE) {
                if(location == null) {
                    return new WeatherResult(null, WeatherResult.ERROR_THROWABLE,
                            "No valid location parameters.", new IllegalArgumentException());
                }
                yqlText = location.replaceAll("[^\\p{L}\\p{Nd} ,-]+", "");
            } else {
                yqlText = String.valueOf(latitude) + ", " + String.valueOf(longitude);
            }
            URL url = new URL("https://query.yahooapis.com/v1/public/yql?q="
                    + URLEncoder.encode("select location.city, units, item.condition, astronomy from weather.forecast where woeid in (select woeid from geo.placefinder where text = \""
                    + yqlText + "\" and gflags = \"R\" limit 1) limit 1", "UTF-8") + "&format=json");
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            try {
                JSONObject response = new JSONObject(IOUtils.toString(connection.getInputStream()));
                if(connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                    JSONObject error = response.getJSONObject("error");
                    return new WeatherResult(null, WeatherResult.ERROR_API, error.getString("description"), null);
                }
                JSONObject query = response.getJSONObject("query");
                if(query.getInt("count") == 0) {
                    return new WeatherResult(null, WeatherResult.ERROR_API, "No results found for that location.", null);
                }

                JSONObject channel = query.getJSONObject("results").getJSONObject("channel");
                JSONObject units = channel.getJSONObject("units");
                JSONObject condition = channel.getJSONObject("item").getJSONObject("condition");
                JSONObject astronomy = channel.getJSONObject("astronomy");

                double temp = condition.getDouble("temp");
                if("F".equals(units.getString("temperature"))) {
                    temp = (temp - 32d) * 5d / 9d;
                }
                String text = condition.getString("text");
                String code = condition.getString("code");
                String date = condition.getString("date");
                String sunrise = astronomy.getString("sunrise");
                String sunset = astronomy.getString("sunset");

                String owmCode = convertYahooCode(code, date, sunrise, sunset);

                if(location == null || location.isEmpty()) {
                    location = channel.getJSONObject("location").getString("city");
                }

                return new WeatherResult(new WeatherData(
                        temp, text, owmCode, latitude, longitude, location, new Date(), Source.YAHOO
                ), WeatherResult.ERROR_NONE, null, null);
            } finally {
                connection.disconnect();
            }
        } catch (Exception ex) {
            return new WeatherResult(null, WeatherResult.ERROR_THROWABLE, ex.getMessage(), ex);
        }
    }

    private static WeatherResult getWeatherFromOWM(double latitude, double longitude, String location) {
        try {
            String query;
            if(latitude == Double.MIN_VALUE && longitude == Double.MIN_VALUE) {
                if(location == null) {
                    return new WeatherResult(null, WeatherResult.ERROR_THROWABLE,
                            "No valid location parameters.", new IllegalArgumentException());
                }
                query = "q=" + URLEncoder.encode(location, "UTF-8");
            } else {
                query = "lat=" + URLEncoder.encode(String.valueOf(latitude), "UTF-8")
                    + "&lon=" + URLEncoder.encode(String.valueOf(longitude), "UTF-8");
            }
            query += "&APPID=" + URLEncoder.encode(BuildConfig.OWM_APPID, "UTF-8");
            URL url = new URL("http://api.openweathermap.org/data/2.5/weather?" + query);
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            try {
                JSONObject response = new JSONObject(IOUtils.toString(connection.getInputStream()));
                if(response.getInt("cod") != HttpURLConnection.HTTP_OK) {
                    // OWM has HTTP error codes that are passed through an API field, the actual HTTP
                    // error code is always 200...
                    return new WeatherResult(null, WeatherResult.ERROR_API,
                            response.getString("cod") + ": " + response.getString("message"), null);
                }

                JSONObject weather = response.getJSONArray("weather").getJSONObject(0);
                JSONObject main = response.getJSONObject("main");

                double temp = main.getDouble("temp") - 273.15d;
                String condition = WordUtils.capitalize(weather.getString("description").trim());
                // Sky Is Clear -> Sky is Clear
                condition = condition.replaceAll("(?<=[^\\w])Is(?=[^\\w]?)", "is");
                String image = weather.getString("icon");

                if(location == null || location.isEmpty()) {
                    location = response.getString("name");
                }

                return new WeatherResult(new WeatherData(
                        temp, condition, image, latitude, longitude, location, new Date(), Source.OPEN_WEATHER_MAP
                ), WeatherResult.ERROR_NONE, null, null);
            } finally {
                connection.disconnect();
            }
        } catch (Exception ex) {
            return new WeatherResult(null, WeatherResult.ERROR_THROWABLE, ex.getMessage(), ex);
        }
    }

    private static String convertYahooCode(String code, String weatherTime, String sunrise, String sunset) {
        Date weatherDate = new Date();
        try {
             weatherDate = YAHOO_DATE_FORMAT.parse(weatherTime);
        } catch (ParseException ex) {
            Log.e(TAG, "Yahoo date format failed!", ex);
        }
        Calendar weatherCal = new GregorianCalendar();
        Calendar sunriseCal = new GregorianCalendar();
        Calendar sunsetCal = new GregorianCalendar();
        weatherCal.setTime(weatherDate);
        sunriseCal.setTime(weatherDate);
        sunsetCal.setTime(weatherDate);

        Matcher sunriseMatch = YAHOO_TIME.matcher(sunrise);
        Matcher sunsetMatch = YAHOO_TIME.matcher(sunset);
        if(!sunriseMatch.matches() || !sunsetMatch.matches()) {
            Log.e(TAG, "Failed to find sunrise/sunset. Using defaults.");
            sunriseMatch = YAHOO_TIME.matcher("6:00 am");
            sunsetMatch = YAHOO_TIME.matcher("6:00 pm");
            sunriseMatch.matches();
            sunsetMatch.matches();
        }
        // Set the sunrise to the correct hour and minute of the same day
        sunriseCal.set(Calendar.HOUR, Integer.parseInt(sunriseMatch.group(1)));
        sunriseCal.set(Calendar.MINUTE, Integer.parseInt(sunriseMatch.group(2)));
        sunriseCal.set(Calendar.SECOND, 0);
        sunriseCal.set(Calendar.MILLISECOND, 0);
        sunriseCal.set(Calendar.AM_PM, "am".equals(sunriseMatch.group(3)) ? Calendar.AM : Calendar.PM);

        // Set the sunset to the correct hour and minute of the same day
        sunsetCal.set(Calendar.HOUR, Integer.parseInt(sunsetMatch.group(1)));
        sunsetCal.set(Calendar.MINUTE, Integer.parseInt(sunsetMatch.group(2)));
        sunsetCal.set(Calendar.SECOND, 0);
        sunsetCal.set(Calendar.MILLISECOND, 0);
        sunsetCal.set(Calendar.AM_PM, "am".equals(sunsetMatch.group(3)) ? Calendar.AM : Calendar.PM);

        boolean isDaytime = true;
        if(weatherCal.before(sunriseCal) || weatherCal.after(sunsetCal)) {
            isDaytime = false;
        }

        String owmCode = "01";
        switch (Integer.parseInt(code)) {
            // Thunderstorms
            case 0: case 1: case 2: case 3: case 4: case 17: case 37: case 38: case 39: case 45: case 47:
                owmCode = "11";
                break;
            // Snow
            case 5: case 7: case 13: case 14: case 15: case 16: case 18: case 41: case 42: case 43: case 46:
                owmCode = "13";
                break;
            // Rain
            case 6: case 10: case 35:
                owmCode = "09";
                break;
            // Light-ish Rain
            case 8: case 9: case 11: case 12: case 40:
                owmCode = "10";
                break;
            // Fog
            case 19: case 20: case 21: case 22:
                owmCode = "50";
                break;
            // Cloudy
            case 27: case 28:
                owmCode = "04";
                break;
            // (Other) Cloudy
            case 26:
                owmCode = "03";
                break;
            // Partly Cloudy
            case 23: case 24: case 25: case 29: case 30: case 44:
                owmCode = "02";
                break;
            // Clear
            case 31: case 32: case 33: case 34: case 36:
                owmCode = "01";
                break;
        }
        return owmCode + (isDaytime ? "d" : "n");
    }

    enum Source {
        OPEN_WEATHER_MAP,
        YAHOO
    }

    final static class WeatherResult {
        final static int ERROR_NONE = 0;
        final static int ERROR_API = 1;
        final static int ERROR_THROWABLE = 2;

        final WeatherData data; // null unless error = ERROR_NONE
        final int error; // corresponds to the above error codes or ERROR_NONE for no error
        final String msg; // null if ERROR_NONE, may or may not be null if there's an error
        final Throwable throwable; // null unless error = ERROR_THROWABLE

        WeatherResult(WeatherData data, int error, String msg, Throwable throwable) {
            this.data = data;
            this.error = error;
            this.msg = msg;
            this.throwable = throwable;
        }
    }

    final static class WeatherData implements Serializable {
        private static final long serialVersionUID = 7141137302093960119L;

        final double temperature; // Always in Celsius
        final String condition;
        final String image; // An OpenWeatherMap weather icon ID
        final double latitude;
        final double longitude;
        final String place;
        final Date time; // The system time the data was retrieved
        final Source source;

        WeatherData(double temperature, String condition, String image, double latitude,
                    double longitude, String place, Date time, Source source) {
            this.temperature = temperature;
            this.condition = condition;
            this.image = image;
            this.latitude = latitude;
            this.longitude = longitude;
            this.place = place;
            this.time = time;
            this.source = source;
        }

        @Override
        public boolean equals(Object o) {
            if(o == null) {
                return false;
            }
            if(getClass() != o.getClass()) {
                return false;
            }
            final WeatherData other = (WeatherData)o;
            return !((this.temperature != other.temperature) || !this.condition.equals(other.condition)
                    || !this.image.equals(other.image) || (this.latitude != other.latitude)
                    || (this.longitude != other.longitude) || !this.place.equals(other.place)
                    || !this.time.equals(other.time) || (this.source != other.source));
        }

        @Override
        public String toString() {
            @SuppressWarnings("StringBufferReplaceableByString")
            StringBuilder sb = new StringBuilder(WeatherData.class.getSimpleName());
            sb.append("[temperature=").append(temperature).append(", condition=").append(condition)
                    .append(", image=").append(image).append(", latitude=").append(latitude)
                    .append(", longitude=").append(longitude).append(", place=").append(place)
                    .append(", time=").append(time).append(", source=").append(source).append("]");
            return sb.toString();
        }
    }
}
