package com.sleepyduck.macdnotification;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import android.os.Handler;
import android.util.Log;

import com.sleepyduck.macdnotification.data.Symbol;

public class CalculateTechnicalAnalysis {
	private static final String LOG_TAG = CalculateTechnicalAnalysis.class.getSimpleName();

	private static final long ONE_DAY = 1000 * 60 * 60 * 24;
	private MACDListener mListener = null;
	private Handler mHandler;

	public CalculateTechnicalAnalysis(MACDListener listener) {
		mListener = listener;
		mHandler = new Handler();
	}

	private URI buildURI(final String symbol) {
		final Calendar calendar = Calendar.getInstance();
		final String end = String.format("%04d-%02d-%02d", calendar.get(Calendar.YEAR),
				calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
		calendar.setTimeInMillis(calendar.getTimeInMillis() - (450L * ONE_DAY));
		final String start = String.format("%04d-%02d-%02d", calendar.get(Calendar.YEAR),
				calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));

		try {
			String query = "select Close from yahoo.finance.historicaldata where startDate=\"" + start
					+ "\" AND symbol=\"" + symbol + "\" AND endDate=\"" + end + "\"";
			query = query.replace(" ", "%20").replace("=", "%3D").replace("\"", "%22").replace("^", "%5E");
			query = "http://query.yahooapis.com/v1/public/yql?q=" + query;
			query += "&env=store%3A%2F%2Fdatatables.org%2Falltableswithkeys";
			return new URI(query);
		} catch (final URISyntaxException e) {
			Log.e(LOG_TAG, "", e);
			return null;
		}
	}

	private List<Float> calcEMA(final List<Float> values, final int days) {
		final ArrayList<Float> ema = new ArrayList<Float>();
		ema.add(calcSMA(values, 0, days));
		final float multiplier = 2.0f / (days + 1);
		for (int i = days; i < values.size(); i++) {
			ema.add(values.get(i) * multiplier + ema.get(ema.size() - 1) * (1.0f - multiplier));
		}
		return ema;
	}

	private float calcSMA(final List<Float> values, int start, int days) {
		if (values.size() < start + days)
			days = values.size() - start;
		float res = 0;
		for (int i = start; i < start+days; i++)
			res += values.get(i);
		return res / days;
	}

	private List<Float> diff(final List<Float> LHS, final List<Float> RHS) {
		final int smallest = Math.min(LHS.size(), RHS.size());
		final List<Float> res = new ArrayList<Float>();
		for (int i = 0; i < smallest; i++)
			res.add(0f);
		for (int i = 1; i <= smallest; i++) {
			res.set(smallest - i, LHS.get(LHS.size() - i) - RHS.get(RHS.size() - i));
		}
		return res;
	}

	private List<Float> calcHighest(final List<Float> values, int days) {
		final ArrayList<Float> high = new ArrayList<Float>();
		for (int i = 0; i < values.size()-days; i++) {
			float res = -1;
			for (int j = i; j < i + days; j++) {
				res = Math.max(res, values.get(j));
			}
			high.add(res);
		}
		return high;
	}

	private List<Float> calcLowest(final List<Float> values, int days) {
		final ArrayList<Float> high = new ArrayList<Float>();
		for (int i = 0; i < values.size()-days; i++) {
			float res = -1;
			for (int j = i; j < i + days; j++) {
				res = Math.min(res, values.get(j));
			}
			high.add(res);
		}
		return high;
	}

	private List<Float> calcPercentile(List<Float> highs, List<Float> lows, List<Float> closeData) {
		final ArrayList<Float> percentile = new ArrayList<Float>();
		final int shortest = Math.min(Math.min(highs.size(), lows.size()), closeData.size());
		int highOffset = highs.size() - shortest;
		int lowOffset = lows.size() - shortest;
		int closeOffset = closeData.size() - shortest;
		for (int i = 0; i < shortest; i++) {
			percentile.add((closeData.get(i+closeOffset) - lows.get(i+lowOffset))/(highs.get(i+highOffset) - lows.get(i+lowOffset)) * 100);
		}
		return percentile;
	}

	private boolean calculateMACD(Symbol symbol, List<Float> closeData) {
		// Reverse the data so that the oldest value is first
		Collections.reverse(closeData);
		if (closeData.size() >= 26) {
			symbol.setDataTime(System.currentTimeMillis());

			// Close
			symbol.setValue(closeData.get(closeData.size() - 1));
			symbol.setValueOld(closeData.get(closeData.size() - 2));

			// MACD
			List<Float> macdLine = diff(calcEMA(closeData, 12), calcEMA(closeData, 26));
			Log.d(LOG_TAG, symbol + " MACD is " + macdLine.get(macdLine.size() - 1)
					+ ", based on " + closeData.size() + " values");
			symbol.setMACD(macdLine.get(macdLine.size() - 1));
			symbol.setMACDOld(macdLine.get(macdLine.size() - 2));

			// MACD Rule #1
			macdLine = diff(calcEMA(closeData, 8), calcEMA(closeData, 17));
			List<Float> histogram = diff(macdLine, calcEMA(macdLine, 9));
			symbol.setRuleNo1Histogram(histogram.get(histogram.size() - 1));
			symbol.setRuleNo1HistogramOld(histogram.get(histogram.size() - 2));

			// Stochastic
			List<Float> k = calcPercentile(calcHighest(closeData, 14), calcLowest(closeData, 14), closeData);
			float d = calcSMA(k, k.size()-6, 5);
			float dOld = calcSMA(k, k.size()-7, 5);
			symbol.setRuleNo1Stochastic(k.get(k.size() - 1) - d);
			symbol.setRuleNo1StochasticOld(k.get(k.size() - 2) - dOld);

			// Moving Average
			float sma10 = calcSMA(closeData, closeData.size()-11, 10);
			float sma10Old = calcSMA(closeData, closeData.size()-12, 10);
			symbol.setRuleNo1SMA(sma10);
			symbol.setRuleNo1SMAOld(sma10Old);
			return true;
		} else if (closeData.size() > 0) {
			String message = "Not enough data for " + symbol + ", only " + closeData.size() + " values found";
			Log.d(LOG_TAG, message);
			publishProgress(message);
		} else {
			String message = symbol + " could not be found";
			Log.d(LOG_TAG, message);
			publishProgress(message);
		}
		return false;
	}

	private String fetchData(URI uri) {
		final HttpClient client = new DefaultHttpClient();
		final HttpGet request = new HttpGet();
		request.setURI(uri);
		try {
			final HttpResponse response = client.execute(request);
			final BufferedReader in = new BufferedReader(new InputStreamReader(response.getEntity()
					.getContent()));
			final StringBuilder sb = new StringBuilder("");
			String l;
			while ((l = in.readLine()) != null) {
				sb.append(l).append("\n");
			}
			return sb.toString();
		} catch (final IOException e) {
			Log.e(LOG_TAG, "", e);
			return null;
		}
	}

	private List<Float> parseData(String uriData) {
		final List<Float> closeData = new ArrayList<Float>();
		try {
			final SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
			parser.parse(new InputSource(new StringReader(uriData)), new DefaultHandler() {
				private boolean mClose = false;

				@Override
				public void characters(final char[] ch, final int start, final int length) throws SAXException {
					super.ignorableWhitespace(ch, start, length);
					if (mClose) {
						final String data = String.copyValueOf(ch, start, length);
						closeData.add(Float.valueOf(data));
					}
				}

				@Override
				public void startElement(final String uri, final String localName, final String qName, final Attributes attributes) throws SAXException {
					super.startElement(uri, localName, qName, attributes);
					mClose = qName.toLowerCase().equals("close");
				}
			});
		} catch (final Exception e) {
			Log.e(LOG_TAG, "", e);
			Log.e(LOG_TAG, "Data: " + uriData);
			return null;
		}
		return closeData;
	}

	private boolean validateData(List<Float> closeData) {
		if (closeData == null)
			return false;
		for (final float val : closeData)
			if (val < 0)
				return false;
		return true;
	}

	private void publishProgress(final String string) {
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mListener != null && string != null)
					mListener.onMessage(string);
			}
		});
	}

	protected void publishResult(final Symbol symbol) {
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mListener != null)
					mListener.onCalculationComplete(symbol);
			}
		});
	}

	public void execute(final Symbol... symbolList) {
		final List<Symbol> synchedSymbols = Collections.synchronizedList(new LinkedList<Symbol>());
		Collections.addAll(synchedSymbols, symbolList);
		for (int i = 0; i < 10; ++i) {
			new Thread() {
				@Override
				public void run() {
					while (synchedSymbols.size() > 0) {
						Symbol symbol = synchedSymbols.remove(0);
						Log.d(LOG_TAG, "Calculate MACD for " + symbol.getName());
						List<Symbol> symbolAsList = symbol.asList();
						for (Symbol sym : symbolAsList) {
							if (sym.hasValidData()) {
								publishResult(sym);
							} else {
								URI uri = buildURI(sym.getName());
								if (uri != null) {
									String uriData = fetchData(uri);
									if (uriData != null) {
										List<Float> closeData = parseData(uriData);
										if (validateData(closeData)) {
											calculateMACD(sym, closeData);
										} else {
											break;
										}
									} else {
										break;
									}
								} else {
									break;
								}
							}
						}
						publishResult(symbol);
					}
				}
			}.start();
		}
	}

	public interface MACDListener {
		public void onMessage(String message);
		public void onCalculationComplete(Symbol symbol);
	}
}