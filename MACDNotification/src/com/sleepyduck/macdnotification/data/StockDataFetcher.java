package com.sleepyduck.macdnotification.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
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

public class StockDataFetcher {
	private static final String LOG_TAG = StockDataFetcher.class.getSimpleName();

	private static final long ONE_DAY = 1000 * 60 * 60 * 24;
	private StockDataListener mListener = null;
	private Handler mHandler;

	public StockDataFetcher(StockDataListener listener) {
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
			String query = "select Adj_Close,High,Low from yahoo.finance.historicaldata where startDate=\"" + start
					+ "\" AND symbol=\"" + symbol + "\" AND endDate=\"" + end + "\"";
			query = query.replace(" ", "%20").replace("=", "%3D").replace("\"", "%22").replace("^", "%5E").replace(",", "%2C");
			query = "http://query.yahooapis.com/v1/public/yql?q=" + query;
			query += "&env=store%3A%2F%2Fdatatables.org%2Falltableswithkeys";
			return new URI(query);
		} catch (final URISyntaxException e) {
			Log.e(LOG_TAG, "", e);
			return null;
		}
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

	private StockDataList parseData(String uriData) {
		final StockDataList data = new StockDataList();
		try {
			final SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
			parser.parse(new InputSource(new StringReader(uriData)), new DefaultHandler() {
				private boolean mClose = false;
				private boolean mHigh = false;
				private boolean mLow = false;

				@Override
				public void characters(final char[] ch, final int start, final int length) throws SAXException {
					super.ignorableWhitespace(ch, start, length);
					final String chars = String.copyValueOf(ch, start, length);
					if (mClose) {
						data.get(0).Close = Float.valueOf(chars);
					} else if (mHigh) {
						data.get(0).High = Float.valueOf(chars);
					} else if (mLow) {
						data.get(0).Low = Float.valueOf(chars);
					}
				}

				@Override
				public void startElement(final String uri, final String localName, final String qName, final Attributes attributes) throws SAXException {
					super.startElement(uri, localName, qName, attributes);
					mClose = qName.toLowerCase().equals("adj_close");
					mHigh = qName.toLowerCase().equals("high");
					mLow = qName.toLowerCase().equals("low");
					if (qName.toLowerCase().equals("quote")) {
						data.add(0, new StockData());
					}
				}
			});
		} catch (final Exception e) {
			Log.e(LOG_TAG, "", e);
			Log.e(LOG_TAG, "Data: " + uriData);
			return null;
		}
		return data;
	}

	private boolean preCalculateIndicators(Symbol symbol, StockDataList data) {
		// Reverse the data so that the oldest value is first
		if (data.size() >= 0) {
			symbol.setStockData(data);
			data.calculateIndicators();
			return true;
		} else {
			String message = symbol + " could not be found";
			Log.d(LOG_TAG, message);
			publishProgress(message);
		}
		return false;
	}

	private boolean validateData(StockDataList data) {
		if (data == null) {
			return false;
		} else if (data.size() == 0)
			return false;
		for (StockData st : data)
			if (st.Close < 0)
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
						Symbol sym = synchedSymbols.remove(0);
						Log.d(LOG_TAG, "Calculate MACD for " + sym.getName());
						if (sym.hasStockData()) {
							publishResult(sym);
						} else {
							URI uri = buildURI(sym.getName());
							if (uri != null) {
								String uriData = fetchData(uri);
								if (uriData != null) {
									StockDataList pData = parseData(uriData);
									if (validateData(pData)) {
										preCalculateIndicators(sym, pData);
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
						publishResult(sym);
					}
				}
			}.start();
		}
	}

	public interface StockDataListener {
		public void onMessage(String message);
		public void onCalculationComplete(Symbol symbol);
	}
}
