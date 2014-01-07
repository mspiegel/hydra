/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.job.spawn;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

import com.addthis.basis.util.Parameter;

import com.addthis.codec.CodecJSON;
import com.addthis.hydra.job.Job;
import com.addthis.hydra.job.JobTask;
import com.addthis.hydra.job.JobTaskState;
import com.addthis.hydra.job.Spawn;
import com.addthis.hydra.job.store.SpawnDataStore;
import com.addthis.hydra.util.EmailUtil;
import com.addthis.maljson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.addthis.hydra.job.store.SpawnDataStoreKeys.SPAWN_COMMON_ALERT_LOADED_LEGACY;
import static com.addthis.hydra.job.store.SpawnDataStoreKeys.SPAWN_COMMON_ALERT_PATH;

/**
 * This class runs a timer that scans the jobs for any email alerts and sends them.
 */
public class JobAlertRunner {

    private static final Logger log = LoggerFactory.getLogger(JobAlertRunner.class);
    private static String clusterName = Parameter.value("spawn.localhost", "unknown");
    private final Spawn spawn;
    private final SpawnDataStore spawnDataStore;

    private static final long REPEAT = Parameter.longValue("spawn.job.alert.repeat", 5 * 60 * 1000);
    private static final long DELAY = Parameter.longValue("spawn.job.alert.delay", 5 * 60 * 1000);

    private static final long GIGA_BYTE = (long) Math.pow(1024, 3);
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMdd-HHmm");
    private static final DecimalFormat decimalFormat = new DecimalFormat("#.###");

    private final ConcurrentHashMap<String, JobAlert> alertMap;

    private static final CodecJSON codec = new CodecJSON();

    private boolean alertsEnabled;


    public JobAlertRunner(Spawn spawn) {
        this.spawn = spawn;
        this.spawnDataStore = spawn.getSpawnDataStore();
        Timer alertTimer = new Timer("JobAlertTimer");
        this.alertsEnabled = spawn.areAlertsEnabled();
        alertTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                scanAlerts();
            }
        }, DELAY, REPEAT);
        this.alertMap = new ConcurrentHashMap<>();
        loadAlertMap();
    }

    /**
     * Method that disables alert scanning
     */
    public void disableAlerts() {
        this.alertsEnabled = false;
    }

    /**
     * Method that enables alert scanning
     */
    public void enableAlerts() {
        this.alertsEnabled = true;
    }

    /**
     * Iterate over alert map, checking the status of each alert.
     */
    public void scanAlerts() {
        if (alertsEnabled) {
            log.info("Running alert scan ..........");
            synchronized (alertMap) {
                for (Map.Entry<String, JobAlert> entry : alertMap.entrySet()) {
                    checkAlert(entry.getValue());
                }
            }
        }
    }

    /**
     * Check whether a particular alert should fire or clear based on job states
     * @param alert The actual alert object
     */
    private void checkAlert(JobAlert alert) {
        boolean alertHasChanged = alert.checkAlertForJobs(getAlertJobs(alert));
        if (alertHasChanged) {
            emailAlert(alert);
        }
    }

    private List<Job> getAlertJobs(JobAlert alert) {
        List<Job> rv = new ArrayList<>();
        if (alert != null && alert.getJobIds() != null) {
            for (String jobId : alert.getJobIds()) {
                Job job = spawn.getJob(jobId);
                if (job != null) {
                    rv.add(job);
                }
            }
        }
        return rv;
    }

    private static String summary(Job job) {
        long files = 0;
        double bytes = 0;
        int running = 0;
        int errored = 0;
        int done = 0;
        int numNodes = 0;

        List<JobTask> jobNodes = job.getCopyOfTasks();

        if (jobNodes != null) {
            numNodes = jobNodes.size();
            for (JobTask task : jobNodes) {
                files += task.getFileCount();
                bytes += task.getByteCount();

                if (!task.getState().equals(JobTaskState.IDLE)) {
                    running++;
                }
                switch (task.getState()) {
                    case IDLE:
                        done++;
                        break;
                    case ERROR:
                        done++;
                        errored++;
                        break;
                }
            }
        }

        StringBuffer sb = new StringBuffer();
        sb.append("Cluster : " + clusterName + "\n");
        sb.append("Job : " + job.getId() + "\n");
        sb.append("Link : http://" + clusterName + ":5052/spawn2/index.html#jobs/" + job.getId() + "/tasks\n");
        sb.append("Description : " + job.getDescription() + "\n");
        sb.append("------------------------------ \n");
        sb.append("Task Summary \n");
        sb.append("------------------------------ \n");
        sb.append("Job State : " + job.getState() + "\n");
        sb.append("Start Time : " + format(job.getStartTime()) + "\n");
        sb.append("End Time : " + format(job.getEndTime()) + "\n");
        sb.append("Num Nodes : " + numNodes + "\n");
        sb.append("Running Nodes : " + running + "\n");
        sb.append("Errored Nodes : " + errored + "\n");
        sb.append("Done Nodes : " + done + "\n");
        sb.append("Task files : " + files + "\n");
        sb.append("Task Bytes : " + format(bytes) + " GB\n");
        sb.append("------------------------------ \n");
        return sb.toString();

    }

    private static String format(double bytes) {
        double gb = bytes / GIGA_BYTE;

        return decimalFormat.format(gb);
    }

    private static String format(Long time) {
        if (time != null) {
            return dateFormat.format(new Date(time));
        } else {
            return "-";
        }
    }

    /**
     * Send an email when an alert fires or clears.
     * @param jobAlert The alert to modify
     */
    private void emailAlert(JobAlert jobAlert) {
        String message = jobAlert.getCurrentStateMessage();
        Map<String, String> activeJobs = jobAlert.getActiveJobs();
        log.info("Alerting " + jobAlert.getEmail() + " :: jobs : " + activeJobs.keySet() + " : " + message);
        String subject = message + " - " + clusterName + " - " + activeJobs.toString();
        StringBuilder sb = new StringBuilder();
        sb.append("Alert: " + jobAlert.getCurrentStateMessage() + " \n");
        for (String jobId : activeJobs.keySet()) {
             sb.append(summary(spawn.getJob(jobId)) + "\n");
        }
        EmailUtil.email(jobAlert.getEmail(), subject, sb.toString());
        putAlert(jobAlert.getAlertId(), jobAlert);
    }

    private void loadAlertMap() {
        synchronized (alertMap) {
            Map<String, String> alertsRaw = spawnDataStore.getAllChildren(SPAWN_COMMON_ALERT_PATH);
            for (Map.Entry<String, String> entry : alertsRaw.entrySet()) {
                loadAlert(entry.getKey(), entry.getValue());
            }
            if (spawnDataStore.get(SPAWN_COMMON_ALERT_LOADED_LEGACY) == null) {
                // One time only, iterate through jobs looking for their alerts.
                try {
                    loadLegacyAlerts();
                    spawnDataStore.put(SPAWN_COMMON_ALERT_LOADED_LEGACY, "1");
                } catch (Exception ex) {
                    log.warn("Warning: failed to fetch legacy alerts", ex);
                }

            }
        }
    }

    private void loadAlert(String id, String raw) {
        try {
            JobAlert jobAlert = codec.decode(new JobAlert(), raw.getBytes());
            alertMap.put(id, jobAlert);
        } catch (Exception ex) {
            log.warn("Failed to decode JobAlert id=" + id + " raw=" + raw);
        }
    }

    public void putAlert(String id, JobAlert alert) {
        JobAlert oldAlert = alertMap.contains(id) ? alertMap.get(id) : null; // ConcurrentHashMap throws NPE on failed 'get'
        if (oldAlert != null) {
            // Inherit old alertTime even if the alert has changed in other ways
            alert.setLastAlertTime(oldAlert.getLastAlertTime());
        }
        alertMap.put(id, alert);
        storeAlert(id, alert);
    }

    public void removeAlert(String id) {
        if (id != null) {
            alertMap.remove(id);
            spawnDataStore.deleteChild(SPAWN_COMMON_ALERT_PATH, id);
        }
    }

    private void loadLegacyAlerts() {
        List<JobAlert> alerts;
        spawn.acquireJobLock();
        try {
            for (Job job : spawn.listJobs()) {
                if (job != null && (alerts = job.getAlerts()) != null) {
                    for (JobAlert alert : alerts) {
                        alert.setJobIds(new String[] {job.getId()});
                        String newUUID = UUID.randomUUID().toString();
                        alert.setAlertId(newUUID);
                        alertMap.put(newUUID, alert);
                        storeAlert(newUUID, alert);
                    }
                    job.setAlerts(null);
                }
            }
        }  finally {
            spawn.releaseJobLock();
        }

    }

    private void storeAlert(String alertId, JobAlert alert) {
        synchronized (alertMap) {
            try {
                spawnDataStore.putAsChild(SPAWN_COMMON_ALERT_PATH, alertId, new String(codec.encode(alert)));
            } catch (Exception e) {
                log.warn("Warning: failed to save alert id=" + alertId + " alert=" + alert);
            }
        }
    }

    /**
     * Get a snapshot of the alert map, mainly for rendering in the UI.
     * @return A JSONObject representation of all existing alerts
     */
    public JSONObject getAlertState() {
        JSONObject rv = new JSONObject();
        synchronized (alertMap) {
            for (Map.Entry<String, JobAlert> entry : alertMap.entrySet()) {
                try {
                    rv.put(entry.getKey(), entry.getValue().toJSON());
                } catch (Exception e) {
                    log.warn("Warning: failed to send alert id=" + entry.getKey() + " alert=" + entry.getValue());
                }
            }
        }
        return rv;
    }


}
