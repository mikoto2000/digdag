package io.digdag.cli;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.google.common.base.Optional;
import io.digdag.core.workflow.WorkflowTask;
import io.digdag.core.repository.WorkflowDefinition;
import io.digdag.core.repository.WorkflowDefinitionList;

public class TaskMatchPattern
{
    public static class SyntaxException
            extends RuntimeException
    {
        public SyntaxException(String message)
        {
            super(message);
        }
    }

    public static class MatchException
            extends Exception
    {
        public MatchException(String message)
        {
            super(message);
        }
    }

    public static class MultipleTaskMatchException
            extends MatchException
    {
        private final Map<Integer, String> matches;

        public MultipleTaskMatchException(String message, Map<Integer, String> matches)
        {
            super(message);
            this.matches = matches;
        }

        public Map<Integer, String> getMatches()
        {
            return matches;
        }
    }

    public static class NoMatchException
            extends MatchException
    {
        public NoMatchException(String message)
        {
            super(message);
        }
    }

    // some symbols are allowed to be in a task name:
    // allowed: - = [ ] { } % & @ , .
    // See also ModelValidator.RAW_TASK_NAME_CHARS
    final static Pattern DELIMITER_PATTERN = Pattern.compile(
            "(?![\\-\\=\\[\\]\\{\\}\\%\\&\\@\\,\\.\\_])(?=[\\W])",
            Pattern.UNICODE_CHARACTER_CLASS);

    public static TaskMatchPattern compile(String pattern)
    {
        String[] fragments = TaskMatchPattern.DELIMITER_PATTERN.split(pattern);
        if (fragments.length == 0) {
            throw new SyntaxException("Task match pattern is invalid: " + pattern);
        }
        for (String f : fragments) {
            if (f.length() == 1) {
                throw new SyntaxException("Match patterns excepting direct child (+name+name) is not supported: " + pattern);
            }
        }
        return new TaskMatchPattern(pattern, fragments);
    }

    private final String pattern;
    private final Pattern regex;

    // TODO support complex patterns

    private TaskMatchPattern(String pattern, String[] fragments)
    {
        this.pattern = pattern;
        this.regex = Pattern.compile(".*" + Pattern.quote(pattern));  // same with String.endsWith
    }

    public String getPattern()
    {
        return pattern;
    }

    public int findIndex(List<WorkflowTask> tasks)
        throws MultipleTaskMatchException, NoMatchException
    {
        Map<Integer, String> all = findAll(tasks);
        if (all.size() == 1) {
            return all.keySet().iterator().next();
        }
        else if (all.isEmpty()) {
            throw new NoMatchException(String.format(
                        "Task pattern '%s' doesn't match with any tasks.", pattern));
        }
        else {
            throw new MultipleTaskMatchException(String.format(
                        "Task pattern '%s' is ambiguous. Matching candidates are %s", pattern, all.values()), all);
        }
    }

    private Map<Integer, String> findAll(List<WorkflowTask> tasks)
    {
        Map<String, WorkflowTask> fullNames = new TaskFullNameResolver(tasks).resolve();
        Map<Integer, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, WorkflowTask> pair : fullNames.entrySet()) {
            if (regex.matcher(pair.getKey()).matches()) {
                map.put(pair.getValue().getIndex(), pair.getKey());
            }
        }
        return map;
    }

    private static class TaskFullNameResolver
    {
        public static Map<String, WorkflowTask> resolve(List<WorkflowTask> tasks)
        {
            return new TaskFullNameResolver(tasks).resolve();
        }

        private final List<Entry> entries;

        private TaskFullNameResolver(List<WorkflowTask> tasks)
        {
            this.entries = tasks.stream()
                .map(task -> new Entry(task))
                .collect(Collectors.toList());
        }

        private Map<String, WorkflowTask> resolve()
        {
            Map<String, WorkflowTask> map = new LinkedHashMap<>();
            for (Entry entry : entries) {
                map.put(entry.getFullName(), entry.getTask());
            }
            return map;
        }

        private class Entry
        {
            private final WorkflowTask task;
            private String fullName;

            public Entry(WorkflowTask task)
            {
                this.task = task;
            }

            public String getFullName()
            {
                if (fullName == null) {
                    if (task.getParentIndex().isPresent()) {
                        fullName = entries.get(task.getParentIndex().get()).getFullName() + task.getName();
                    }
                    else {
                        fullName = task.getName();
                    }
                }
                return fullName;
            }

            public WorkflowTask getTask()
            {
                return task;
            }
        }
    }
}
