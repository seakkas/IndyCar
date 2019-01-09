package iu.edu.indycar.streamer;

import iu.edu.indycar.streamer.exceptions.NotParsableException;
import iu.edu.indycar.streamer.records.EntryRecord;
import iu.edu.indycar.streamer.records.IndycarRecord;
import iu.edu.indycar.streamer.records.TelemetryRecord;
import iu.edu.indycar.streamer.records.WeatherRecord;
import iu.edu.indycar.streamer.records.parsers.EntryRecordParser;
import iu.edu.indycar.streamer.records.parsers.TelemetryRecordParser;
import iu.edu.indycar.streamer.records.parsers.WeatherRecordParser;
import iu.edu.indycar.streamer.records.policy.AbstractRecordAcceptPolicy;
import iu.edu.indycar.streamer.records.policy.DefaultRecordAcceptPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class RecordStreamer {

    private final static Logger LOG = LogManager.getLogger(RecordStreamer.class);

    //Listeners
    private RecordListener<IndycarRecord> recordListener;
    private RecordListener<WeatherRecord> weatherRecordListener;
    private RecordListener<TelemetryRecord> telemetryRecordListener;
    private RecordListener<EntryRecord> entryRecordRecordListener;

    private File file;
    private boolean realTiming;
    private int speed = 1;

    private boolean fileEnded;

    private FileNameDateExtractor dateExtractor;

    private ConcurrentHashMap<String, RecordTiming> records = new ConcurrentHashMap<>();

    private HashMap<Class<? extends IndycarRecord>,
            AbstractRecordAcceptPolicy> recordAcceptPolicies = new HashMap<>();

    public RecordStreamer(File file, boolean realTiming, FileNameDateExtractor dateExtractor) {
        this.file = file;
        this.realTiming = realTiming;
        this.dateExtractor = dateExtractor;
    }

    public RecordStreamer(File file, boolean realTiming, int speed, FileNameDateExtractor dateExtractor) {
        this(file, realTiming, dateExtractor);
        this.speed = speed;
    }

    public void setRecordListener(RecordListener<IndycarRecord> recordListener) {
        this.recordListener = recordListener;
    }

    public void setTelemetryRecordListener(
            RecordListener<TelemetryRecord> telemetryRecordRecordListener) {
        this.telemetryRecordListener = telemetryRecordRecordListener;
    }

    public void setWeatherRecordListener(
            RecordListener<WeatherRecord> weatherRecordRecordListener) {
        this.weatherRecordListener = weatherRecordRecordListener;
    }

    public void setEntryRecordRecordListener(RecordListener<EntryRecord> entryRecordRecordListener) {
        this.entryRecordRecordListener = entryRecordRecordListener;
    }

    public void start() {
        new Thread(() -> {
            try {
                readFile();
            } catch (IOException e) {
                System.out.println("Error in reading files");
            }
        }, "file-reader").start();
    }

    private void queueEvent(IndycarRecord indycarRecord) {
        if (!realTiming || !indycarRecord.isTimeSensitive()) {
            this.publishEvent(indycarRecord);
        } else {
            try {
                this.records.computeIfAbsent(indycarRecord.getGroupTag(),
                        (s) -> new RecordTiming(
                                indycarRecord.getGroupTag(),
                                this::publishEvent,
                                this.speed)
                ).enqueue(indycarRecord);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void publishEvent(IndycarRecord indycarRecord) {
        if (this.recordListener != null) {
            this.recordListener.onRecord(indycarRecord);
        }

        if (this.telemetryRecordListener != null
                && indycarRecord instanceof TelemetryRecord) {
            this.telemetryRecordListener.onRecord((TelemetryRecord) indycarRecord);
        } else if (this.weatherRecordListener != null
                && indycarRecord instanceof WeatherRecord) {
            this.weatherRecordListener.onRecord((WeatherRecord) indycarRecord);
        } else if (this.entryRecordRecordListener != null && indycarRecord instanceof EntryRecord) {
            this.entryRecordRecordListener.onRecord((EntryRecord) indycarRecord);
        }
    }

    public void addRecordAcceptPolicy(Class<? extends IndycarRecord> clazz, AbstractRecordAcceptPolicy tRecord) {
        this.recordAcceptPolicies.put(clazz, tRecord);
    }

    private void readFile() throws IOException {
        FileReader fis = new FileReader(file);

        String date = this.dateExtractor.extractDate(file.getName());

        BufferedReader br = new BufferedReader(fis);
        String line = br.readLine();

        TelemetryRecordParser telemetryRecordParser = new TelemetryRecordParser("�");
        WeatherRecordParser weatherRecordParser = new WeatherRecordParser("�");
        EntryRecordParser entryRecordParser = new EntryRecordParser("�");

        while (line != null) {
            try {
                IndycarRecord record = null;
                if (line.startsWith("$P")) {
                    TelemetryRecord tr = telemetryRecordParser.parse(line);
                    tr.setDate(date);
                    record = tr;
                } else if (line.startsWith("$W")) {
                    record = weatherRecordParser.parse(line);
                } else if (line.startsWith("$E")) {
                    record = entryRecordParser.parse(line);
                }
                if (record != null && this.recordAcceptPolicies.getOrDefault(
                        record.getClass(), DefaultRecordAcceptPolicy.getInstance()).evaluate(record)) {
                    this.queueEvent(record);
                }
            } catch (NotParsableException e) {
                if (e.isLog()) {
                    LOG.warn("Couldn't parse a record", e);
                }
                //couldn't parse
            } finally {
                line = br.readLine();
            }
        }
        br.close();
        this.fileEnded = true;
        System.out.println("End of File : " + file.getName());
    }
}
