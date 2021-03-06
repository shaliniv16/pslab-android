package io.pslab.fragment;

import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.github.anastr.speedviewlib.PointerSpeedometer;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import io.pslab.R;
import io.pslab.activity.LuxMeterActivity;
import io.pslab.communication.ScienceLab;
import io.pslab.communication.peripherals.I2C;
import io.pslab.communication.sensors.BH1750;
import io.pslab.communication.sensors.TSL2561;
import io.pslab.models.LuxData;
import io.pslab.models.SensorDataBlock;
import io.pslab.others.ScienceLabCommon;

import static android.content.Context.SENSOR_SERVICE;

/**
 * Created by Padmal on 11/2/18.
 */

public class LuxMeterDataFragment extends Fragment {

    private static int sensorType = 0;
    private static int highLimit = 2000;
    private static int updatePeriod = 100;
    private static int gain = 1;
    private long timeElapsed;
    private int count = 0, turns = 0;
    private float sum = 0;
    private boolean returningFromPause = false;

    private float luxValue = -1;

    private enum LUX_SENSOR {INBUILT_SENSOR, BH1750_SENSOR, TSL2561_SENSOR}

    @BindView(R.id.lux_max)
    TextView statMax;
    @BindView(R.id.lux_min)
    TextView statMin;
    @BindView(R.id.lux_avg)
    TextView statMean;
    @BindView(R.id.label_lux_sensor)
    TextView sensorLabel;
    @BindView(R.id.chart_lux_meter)
    LineChart mChart;
    @BindView(R.id.light_meter)
    PointerSpeedometer lightMeter;

    private Timer graphTimer;
    private SensorManager sensorManager;
    private Sensor sensor;
    private long startTime, block;
    private ArrayList<Entry> entries;
    private ArrayList<LuxData> recordedLuxArray;
    private LuxData sensorData;
    private float currentMin = 10000;
    private float currentMax = 0;
    private YAxis y;
    private Unbinder unbinder;
    private long previousTimeElapsed = (System.currentTimeMillis() - startTime) / updatePeriod;
    private LuxMeterActivity luxSensor;

    public static LuxMeterDataFragment newInstance() {
        return new LuxMeterDataFragment();
    }

    public static void setParameters(int highLimit, int updatePeriod, String type, String gain) {
        LuxMeterDataFragment.highLimit = highLimit;
        LuxMeterDataFragment.updatePeriod = updatePeriod;
        LuxMeterDataFragment.sensorType = Integer.valueOf(type);
        LuxMeterDataFragment.gain = Integer.valueOf(gain);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startTime = System.currentTimeMillis();
        entries = new ArrayList<>();
        luxSensor = (LuxMeterActivity) getActivity();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_lux_meter_data, container, false);
        unbinder = ButterKnife.bind(this, view);
        setupInstruments();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (luxSensor.playingData) {
            sensorLabel.setText(getResources().getString(R.string.recorder));
            recordedLuxArray = new ArrayList<>();
            resetInstrumentData();
            playRecordedData();
        } else if (luxSensor.viewingData) {
            sensorLabel.setText(getResources().getString(R.string.recorder));
            recordedLuxArray = new ArrayList<>();
            resetInstrumentData();
            plotAllRecordedData();
            // TODO: Display the whole graph with filled data
        } else if (!luxSensor.isRecording) {
            updateGraphs();
            sum = 0;
            count = 0;
            currentMin = 10000;
            currentMax = 0;
            entries.clear();
            mChart.clear();
            mChart.invalidate();
            initiateLuxSensor(sensorType);
        } else if (returningFromPause) {
            updateGraphs();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (graphTimer != null) {
            graphTimer.cancel();
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(lightSensorEventListener);
        }
        unbinder.unbind();
    }

    private void plotAllRecordedData() {
        recordedLuxArray.addAll(luxSensor.recordedLuxData);
        if (recordedLuxArray.size() != 0) {
            for (LuxData d: recordedLuxArray) {
                if (currentMax < d.getLux()) {
                    currentMax = d.getLux();
                }
                if (currentMin > d.getLux()) {
                    currentMin = d.getLux();
                }
                Entry entry = new Entry((float) (d.getTime() - d.getBlock()) / 1000, d.getLux());
                entries.add(entry);
                sum += entry.getY();
            }
            y.setAxisMaximum(currentMax);
            y.setAxisMinimum(currentMin);
            y.setLabelCount(10);
            statMax.setText(String.valueOf(currentMax));
            statMin.setText(String.valueOf(currentMin));
            statMean.setText(String.format(Locale.getDefault(), "%.2f", (sum / recordedLuxArray.size())));

            LineDataSet dataSet = new LineDataSet(entries, getString(R.string.lux));
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);
            dataSet.setLineWidth(2);
            LineData data = new LineData(dataSet);

            mChart.setData(data);
            mChart.notifyDataSetChanged();
            mChart.setVisibleXRangeMaximum(80);
            mChart.moveViewToX(data.getEntryCount());
            mChart.invalidate();
        }
    }

    private void playRecordedData() {
        recordedLuxArray.addAll(luxSensor.recordedLuxData);
        try {
            if (recordedLuxArray.size() > 1) {
                LuxData i = recordedLuxArray.get(1);
                long timeGap = i.getTime() - i.getBlock();
                processRecordedData(timeGap);
            } else {
                processRecordedData(0);
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(getActivity(),
                    getActivity().getResources().getString(R.string.no_data_fetched), Toast.LENGTH_SHORT).show();
        }
    }

    private void processRecordedData(long timeGap) {
        final Handler handler = new Handler();
        if (graphTimer != null) {
            graphTimer.cancel();
        } else {
            graphTimer = new Timer();
        }
        graphTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (luxSensor.playingData) {
                            try {
                                LuxData d = recordedLuxArray.get(turns);
                                turns++;
                                if (currentMax < d.getLux()) {
                                    currentMax = d.getLux();
                                    statMax.setText(String.valueOf(d.getLux()));
                                }
                                if (currentMin > d.getLux()) {
                                    currentMin = d.getLux();
                                    statMin.setText(String.valueOf(d.getLux()));
                                }
                                y.setAxisMaximum(currentMax);
                                y.setAxisMinimum(currentMin);
                                y.setLabelCount(10);
                                lightMeter.setWithTremble(false);
                                lightMeter.setSpeedAt(d.getLux());

                                Entry entry = new Entry((float) (d.getTime() - d.getBlock()) / 1000, d.getLux());
                                entries.add(entry);
                                count++;
                                sum += entry.getY();
                                statMean.setText(String.format(Locale.getDefault(), "%.2f", (sum / count)));

                                LineDataSet dataSet = new LineDataSet(entries, getString(R.string.lux));
                                dataSet.setDrawCircles(false);
                                dataSet.setDrawValues(false);
                                dataSet.setLineWidth(2);
                                LineData data = new LineData(dataSet);

                                mChart.setData(data);
                                mChart.notifyDataSetChanged();
                                mChart.setVisibleXRangeMaximum(80);
                                mChart.moveViewToX(data.getEntryCount());
                                mChart.invalidate();
                            } catch (IndexOutOfBoundsException e) {
                                graphTimer.cancel();
                                graphTimer = null;
                                turns = 0;
                                luxSensor.playingData = false;
                                luxSensor.startedPlay = false;
                                luxSensor.invalidateOptionsMenu();
                            }
                        }
                    }
                });
            }
        }, 0, timeGap);
    }

    public void playData() {
        resetInstrumentData();
        luxSensor.startedPlay = true;
        try {
            if (recordedLuxArray.size() > 1) {
                LuxData i = recordedLuxArray.get(1);
                long timeGap = i.getTime() - i.getBlock();
                processRecordedData(timeGap);
            } else {
                processRecordedData(0);
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(getActivity(),
                    getActivity().getResources().getString(R.string.no_data_fetched), Toast.LENGTH_SHORT).show();
        }
    }

    public void saveGraph() {
        // Todo: Implement export here
    }

    private void setupInstruments() {
        lightMeter.setMaxSpeed(10000);

        XAxis x = mChart.getXAxis();
        this.y = mChart.getAxisLeft();
        YAxis y2 = mChart.getAxisRight();

        mChart.setTouchEnabled(true);
        mChart.setHighlightPerDragEnabled(true);
        mChart.setDragEnabled(true);
        mChart.setScaleEnabled(true);
        mChart.setDrawGridBackground(false);
        mChart.setPinchZoom(true);
        mChart.setScaleYEnabled(true);
        mChart.setBackgroundColor(Color.BLACK);
        mChart.getDescription().setEnabled(false);

        LineData data = new LineData();
        mChart.setData(data);

        Legend l = mChart.getLegend();
        l.setForm(Legend.LegendForm.LINE);
        l.setTextColor(Color.WHITE);

        x.setTextColor(Color.WHITE);
        x.setDrawGridLines(true);
        x.setAvoidFirstLastClipping(true);

        y.setTextColor(Color.WHITE);
        y.setAxisMaximum(currentMax);
        y.setAxisMinimum(currentMin);
        y.setDrawGridLines(true);
        y.setLabelCount(10);

        y2.setDrawGridLines(false);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (graphTimer != null) {
            returningFromPause = true;
            graphTimer.cancel();
            graphTimer = null;
            if (luxSensor.playingData) {
                luxSensor.finish();
            }
        }
    }

    private void updateGraphs() {
        final Handler handler = new Handler();
        if (graphTimer != null) {
            graphTimer.cancel();
        }
        graphTimer = new Timer();
        graphTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            visualizeData();
                        } catch (NullPointerException e) {
                            /* Pass for another refresh round */
                        }
                    }
                });
            }
        }, 0, updatePeriod);
    }

    private void writeLogToFile(long timestamp, float sensorReading) {
        if (getActivity() != null && luxSensor.isRecording) {
            if (luxSensor.writeHeaderToFile) {
                luxSensor.csvLogger.prepareLogFile();
                luxSensor.csvLogger.writeCSVFile("Timestamp,DateTime,Readings,Latitude,Longitude");
                block = timestamp;
                luxSensor.recordSensorDataBlockID(new SensorDataBlock(timestamp, luxSensor.getSensorName()));
                luxSensor.writeHeaderToFile = !luxSensor.writeHeaderToFile;
            }
            if (luxSensor.addLocation && luxSensor.gpsLogger.isGPSEnabled()) {
                String dateTime = luxSensor.dateFormat.format(new Date(timestamp));
                Location location = luxSensor.gpsLogger.getDeviceLocation();
                luxSensor.csvLogger.writeCSVFile(timestamp + "," + dateTime + ","
                        + sensorReading + "," + location.getLatitude() + "," + location.getLongitude());
                sensorData = new LuxData(timestamp, block, luxValue, location.getLatitude(), location.getLongitude());
            } else {
                String dateTime = luxSensor.dateFormat.format(new Date(timestamp));
                luxSensor.csvLogger.writeCSVFile(timestamp + "," + dateTime + ","
                        + sensorReading + ",0.0,0.0");
                sensorData = new LuxData(timestamp, block, luxValue, 0.0, 0.0);
            }
            luxSensor.recordSensorData(sensorData);
        } else {
            luxSensor.writeHeaderToFile = true;
        }
    }

    private void visualizeData() {
        if (currentMax < luxValue) {
            currentMax = luxValue;
            statMax.setText(String.valueOf(luxValue));
        }
        if (currentMin > luxValue) {
            currentMin = luxValue;
            statMin.setText(String.valueOf(luxValue));
        }
        y.setAxisMaximum(currentMax);
        y.setAxisMinimum(currentMin);
        y.setLabelCount(10);
        if (luxValue >= 0) {
            lightMeter.setWithTremble(false);
            lightMeter.setSpeedAt(luxValue);
            if (luxValue > highLimit)
                lightMeter.setPointerColor(Color.RED);
            else
                lightMeter.setPointerColor(Color.WHITE);

            timeElapsed = ((System.currentTimeMillis() - startTime) / updatePeriod);
            if (timeElapsed != previousTimeElapsed) {
                previousTimeElapsed = timeElapsed;
                Entry entry = new Entry((float) timeElapsed, luxValue);
                Long currentTime = System.currentTimeMillis();
                writeLogToFile(currentTime, luxValue);
                entries.add(entry);

                count++;
                sum += entry.getY();
                statMean.setText(String.format(Locale.getDefault(), "%.2f", (sum / count)));

                LineDataSet dataSet = new LineDataSet(entries, getString(R.string.lux));
                dataSet.setDrawCircles(false);
                dataSet.setDrawValues(false);
                dataSet.setLineWidth(2);
                LineData data = new LineData(dataSet);

                mChart.setData(data);
                mChart.notifyDataSetChanged();
                mChart.setVisibleXRangeMaximum(80);
                mChart.moveViewToX(data.getEntryCount());
                mChart.invalidate();
            }
        }
    }

    private SensorEventListener lightSensorEventListener = new SensorEventListener() {

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {/**/}

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
                luxValue = event.values[0];
            }
        }
    };

    private void resetInstrumentData() {
        luxValue = 0;
        count = 0;
        currentMin = 10000;
        currentMax = 0;
        sum = 0;
        sensor = null;
        if (sensorManager != null) {
            sensorManager.unregisterListener(lightSensorEventListener);
        }
        startTime = System.currentTimeMillis();
        statMax.setText(getResources().getString(R.string.value_null));
        statMin.setText(getResources().getString(R.string.value_null));
        statMean.setText(getResources().getString(R.string.value_null));
        lightMeter.setSpeedAt(0);
        lightMeter.setWithTremble(false);
        entries.clear();
    }

    private void initiateLuxSensor(int type) {
        LUX_SENSOR s = LUX_SENSOR.values()[type];
        resetInstrumentData();
        ScienceLab scienceLab;
        switch (s) {
            case INBUILT_SENSOR:
                sensorLabel.setText(getResources().getStringArray(R.array.lux_sensors)[0]);
                sensorManager = (SensorManager) getContext().getSystemService(SENSOR_SERVICE);
                sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                if (sensor == null) {
                    Toast.makeText(getContext(), getResources().getString(R.string.no_lux_sensor), Toast.LENGTH_LONG).show();
                } else {
                    float max = sensor.getMaximumRange();
                    lightMeter.setMaxSpeed(max);
                    sensorManager.registerListener(lightSensorEventListener,
                            sensor, SensorManager.SENSOR_DELAY_FASTEST);
                }
                break;
            case BH1750_SENSOR:
                sensorLabel.setText(getResources().getStringArray(R.array.lux_sensors)[1]);
                scienceLab = ScienceLabCommon.scienceLab;
                if (scienceLab.isConnected()) {
                    ArrayList<Integer> data;
                    try {
                        I2C i2c = scienceLab.i2c;
                        data = i2c.scan(null);
                        if (data.contains(0x23)) {
                            BH1750 sensorBH1750 = new BH1750(i2c);
                            sensorBH1750.setRange(String.valueOf(gain));
                            sensorType = 0;
                        } else {
                            Toast.makeText(getContext(), getResources().getText(R.string.sensor_not_connected_tls), Toast.LENGTH_SHORT).show();
                            sensorType = 0;
                        }
                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    Toast.makeText(getContext(), getResources().getText(R.string.device_not_found), Toast.LENGTH_SHORT).show();
                    sensorType = 0;
                }

                break;
            case TSL2561_SENSOR:
                sensorLabel.setText(getResources().getStringArray(R.array.lux_sensors)[2]);
                scienceLab = ScienceLabCommon.scienceLab;
                if (scienceLab.isConnected()) {
                    try {
                        I2C i2c = scienceLab.i2c;
                        ArrayList<Integer> data;
                        data = i2c.scan(null);
                        if (data.contains(0x39)) {
                            TSL2561 sensorTSL2561 = new TSL2561(i2c, scienceLab);
                            sensorTSL2561.setGain(String.valueOf(gain));
                            sensorType = 2;
                        } else {
                            Toast.makeText(getContext(), getResources().getText(R.string.sensor_not_connected_tls), Toast.LENGTH_SHORT).show();
                            sensorType = 0;
                        }
                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    Toast.makeText(getContext(), getResources().getText(R.string.device_not_found), Toast.LENGTH_SHORT).show();
                    sensorType = 0;
                }
                break;
            default:
                break;
        }
    }
}
