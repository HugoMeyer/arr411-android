package dev.c411.qbittorrent;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** The periodic check of the update. The work runs on the thread of the updater: this only starts it, and tells the scheduler when it is over. */
public final class UpdateJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            return Updater.get(this).runScheduled(Updater.once(() -> jobFinished(params, false)));
        } catch (Throwable e) {
            Diag.problem("update-job-start", "update: the periodic check could not start [" + e.getClass().getSimpleName() + "]", null);
            return false;
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        try {
            Updater.get(this).cancelWork();
        } catch (Throwable ignored) {
        }
        return false;
    }
}
