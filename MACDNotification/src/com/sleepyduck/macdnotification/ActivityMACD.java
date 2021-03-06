package com.sleepyduck.macdnotification;

import java.util.List;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.Spinner;
import android.widget.Toast;

import com.sleepyduck.macdnotification.data.DataController;
import com.sleepyduck.macdnotification.data.Group;
import com.sleepyduck.macdnotification.data.RetrieveDisplayName;
import com.sleepyduck.macdnotification.data.RetrieveDisplayName.RetrieveDisplayNameListener;
import com.sleepyduck.macdnotification.data.StockDataFetcher;
import com.sleepyduck.macdnotification.data.Symbol;

public class ActivityMACD extends Activity {
	public static final String ACTION_BROADCAST_REMOVE = "ActivityMACD:action_broadcast_remove";
	public static final String DATA_REMOVED_SYMBOL = "removed_symbol";

	private ExpandableListAdapter mListAdapter;
	private Spinner mGroupSpinner;
	private ArrayAdapter<Group> mSpinnerAdapter;
	private View mAddLayout;
	private View mRuleNo1Layout;
	private EditText mNameEditText;
	private EditText mRuleNo1ValuationEditText;
	private final DataController mDataController = new DataController();

	private final StockDataFetcher.StockDataListener mStockDataListener = new StockDataFetcher.StockDataListener() {
		@Override
		public void onMessage(String message) {
			Toast.makeText(ActivityMACD.this, message, Toast.LENGTH_LONG).show();
		}

		@Override
		public void onCalculationComplete(Symbol symbol) {
			if (!symbol.hasStockData() && symbol.doRetry())
				mStockDataFetcher.execute(symbol);
			else
				mListAdapter.notifyDataSetChanged();
		}
	};
	private final StockDataFetcher mStockDataFetcher = new StockDataFetcher(mStockDataListener);

	private final RetrieveDisplayNameListener mDisplayNameListener = new RetrieveDisplayNameListener() {
		@Override
		public void onRetrieveComplete(Symbol symbol) {
			mListAdapter.notifyDataSetChanged();
		}
	};
	private final RetrieveDisplayName mRetrieveDisplayName = new RetrieveDisplayName(mDisplayNameListener);

	private BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.hasExtra(DATA_REMOVED_SYMBOL)) {
				Symbol symbol = (Symbol) intent.getSerializableExtra(DATA_REMOVED_SYMBOL);
				mGroupSpinner.setVisibility(View.VISIBLE);
				mRuleNo1Layout.setVisibility(View.VISIBLE);
				mAddLayout.setVisibility(View.VISIBLE);
				if (symbol != null && mNameEditText != null && mRuleNo1ValuationEditText != null) {
					mNameEditText.setText(symbol.getName());
					mNameEditText.setHint(R.string.symbol_name);
					mNameEditText.requestFocus();
					mRuleNo1ValuationEditText.setText(symbol.getRuleNo1Valuation() != null ?
							String.valueOf(symbol.getRuleNo1Valuation()) : "");
				}
			}
		}
	};

	private ExpandableListView.OnChildClickListener mChildClickListener =
			new ExpandableListView.OnChildClickListener() {
		@Override
		public boolean onChildClick(ExpandableListView expandableListView, View view, int groupIndex, int symbolIndex, long id) {
			Symbol symbol = mDataController.getGroup(groupIndex).getSymbol(symbolIndex);
			Intent intent = new Intent(ActivityMACD.this, ActivityStockView.class);
			intent.putExtra("symbol", symbol);
			ActivityMACD.this.startActivity(intent);
			return true;
		}
	};

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_macd);

		mGroupSpinner = (Spinner) findViewById(R.id.spinnerGroup);
		mRuleNo1Layout = findViewById(R.id.ruleNo1Layout);
		mAddLayout = findViewById(R.id.addLayout);
		mNameEditText = (EditText) findViewById(R.id.editTextNewSymbol);
		mRuleNo1ValuationEditText = (EditText) findViewById(R.id.editTextRuleNo1Value);

		final ExpandableListView mListView = (ExpandableListView) findViewById(R.id.listView);

		mSpinnerAdapter = new ArrayAdapter<Group>(this, android.R.layout.simple_spinner_item, mDataController.getGroups());
		mGroupSpinner.setAdapter(mSpinnerAdapter);

		mListAdapter = new ExpandableListAdapter(this, mDataController.getGroups());
		mListView.setAdapter(mListAdapter);
		mListView.setOnChildClickListener(mChildClickListener);

		if (!mDataController.load(savedInstanceState)) {
			mDataController.loadFromFile(this);
			List<Symbol> dataList = mDataController.getAllSymbols();
			mStockDataFetcher.execute(dataList.toArray(new Symbol[dataList.size()]));
			mRetrieveDisplayName.execute(dataList.toArray(new Symbol[dataList.size()]));
		}

		mListAdapter.notifyDataSetChanged();
		mSpinnerAdapter.notifyDataSetChanged();

		registerReceiver(mReceiver, new IntentFilter(ACTION_BROADCAST_REMOVE));
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		mDataController.save(outState);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		unregisterReceiver(mReceiver);
		mDataController.saveToFile();
	}

	public void onAddSymbolClicked(final View view) {
		mAddLayout.setVisibility(View.GONE);
		if (mGroupSpinner.getVisibility() == View.VISIBLE) {
			// Add Symbol
			if (mNameEditText != null
					&& mNameEditText.getText() != null
					&& !mNameEditText.getText().toString().equals("")) {
				String symbolName = mNameEditText.getText().toString();
				Float ruleNo1Valuation = null;
				try {
					ruleNo1Valuation = Float.valueOf(mRuleNo1ValuationEditText.getText().toString());
				} catch (NumberFormatException e) { }
				Symbol symbol = mDataController.addSymbol(mGroupSpinner.getSelectedItemPosition(), symbolName, ruleNo1Valuation);
				mStockDataFetcher.execute(symbol);
				mRetrieveDisplayName.execute(symbol);
			}
		} else {
			// Add group
			if (mNameEditText != null && mNameEditText.getText() != null
					&& !mNameEditText.getText().toString().equals("")) {
				if (mDataController.getGroupIndex(mNameEditText.getText().toString()) > -1) {
					Toast.makeText(this, "That group alreay exists", Toast.LENGTH_LONG).show();
					mAddLayout.setVisibility(View.VISIBLE);
				} else {
					mDataController.addGroup(mNameEditText.getText().toString());
				}
			}
		}
		mListAdapter.notifyDataSetChanged();
		mSpinnerAdapter.notifyDataSetChanged();
	}

	public void onNewGroupClicked(final View view) {
		if (mGroupSpinner.getVisibility() == View.VISIBLE || mAddLayout.getVisibility() == View.GONE) {
			mGroupSpinner.setVisibility(View.GONE);
			mRuleNo1Layout.setVisibility(View.GONE);
			if (mNameEditText != null) {
				mNameEditText.setText("");
				mNameEditText.setHint(R.string.group_name);
			}
			mAddLayout.setVisibility(View.VISIBLE);
		} else {
			mAddLayout.setVisibility(View.GONE);
		}
	}

	public void onNewSymbolClicked(final View view) {
		if (mGroupSpinner.getVisibility() == View.GONE || mAddLayout.getVisibility() == View.GONE) {
			mGroupSpinner.setVisibility(View.VISIBLE);
			mRuleNo1Layout.setVisibility(View.VISIBLE);
			if (mNameEditText != null) {
				mNameEditText.setText("");
				mNameEditText.setHint(R.string.symbol_name);
				mRuleNo1ValuationEditText.setText("");
			}
			mAddLayout.setVisibility(View.VISIBLE);
		} else {
			mAddLayout.setVisibility(View.GONE);
		}
	}
}
