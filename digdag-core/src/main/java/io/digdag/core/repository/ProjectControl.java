package io.digdag.core.repository;

import java.util.List;
import java.time.Instant;
import com.google.common.collect.ImmutableList;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import io.digdag.client.config.Config;
import io.digdag.core.schedule.Schedule;
import io.digdag.core.schedule.ScheduleStoreManager;
import io.digdag.core.schedule.SchedulerManager;
import io.digdag.core.schedule.ScheduleExecutor;
import io.digdag.client.config.ConfigException;
import io.digdag.spi.ScheduleTime;
import io.digdag.spi.Scheduler;
import java.util.stream.Collectors;

public class ProjectControl
{
    private final ProjectControlStore store;
    private final StoredProject project;

    public ProjectControl(ProjectControlStore store, StoredProject project)
    {
        this.store = store;
        this.project = project;
    }

    public StoredProject get()
    {
        return project;
    }

    public StoredRevision insertRevision(Revision revision)
        throws ResourceConflictException
    {
        return store.insertRevision(project.getId(), revision);
    }

    public void insertRevisionArchiveData(int revId, byte[] data)
        throws ResourceConflictException
    {
        store.insertRevisionArchiveData(revId, data);
    }

    public List<StoredWorkflowDefinition> insertWorkflowDefinitions(
            StoredRevision revision, List<WorkflowDefinition> defs,
            SchedulerManager srm, Instant currentTime)
        throws ResourceConflictException
    {
        List<StoredWorkflowDefinition> list = insertWorkflowDefinitionsWithoutSchedules(revision, defs);
        updateSchedules(revision, list, srm, currentTime);
        return list;
    }

    public List<StoredWorkflowDefinition> insertWorkflowDefinitionsWithoutSchedules(
            StoredRevision revision, List<WorkflowDefinition> defs)
        throws ResourceConflictException
    {
        try {
            return defs.stream()
                .map(def -> {
                    try {
                        return store.insertWorkflowDefinition(project.getId(), revision.getId(), def, def.getTimeZone());
                    }
                    catch (ResourceConflictException ex) {
                        throw new IllegalStateException("Database state error", ex);
                    }
                })
                .collect(Collectors.toList());
        }
        catch (IllegalStateException ex) {
            Throwables.propagateIfInstanceOf(ex.getCause(), ResourceConflictException.class);
            throw ex;
        }
    }

    private void updateSchedules(
            StoredRevision revision, List<StoredWorkflowDefinition> defs,
            SchedulerManager srm, Instant currentTime)
        throws ResourceConflictException
    {
        ImmutableList.Builder<Schedule> schedules = ImmutableList.builder();
        for (StoredWorkflowDefinition def : defs) {
            Optional<Scheduler> sr = srm.tryGetScheduler(revision, def);
            if (sr.isPresent()) {
                ScheduleTime firstTime = sr.get().getFirstScheduleTime(currentTime);
                Schedule schedule = Schedule.of(def.getName(), def.getId(), firstTime.getRunTime(), firstTime.getTime());
                schedules.add(schedule);
            }
        }

        // TODO validate workflows and sessions
        //   * compile workflow
        //   * validate SubtaskMatchPattern

        store.updateSchedules(project.getId(), schedules.build());
    }
}
