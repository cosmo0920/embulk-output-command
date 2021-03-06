package org.embulk.output;

import java.util.List;
import java.util.ArrayList;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import org.embulk.config.TaskReport;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.Buffer;
import org.embulk.spi.Exec;
import org.embulk.spi.FileOutputPlugin;
import org.embulk.spi.TransactionalFileOutput;

public class CommandFileOutputPlugin
        implements FileOutputPlugin
{
    private final Logger logger = Exec.getLogger(getClass());

    public interface PluginTask
            extends Task
    {
        @Config("command")
        public String getCommand();
    }

    @Override
    public ConfigDiff transaction(ConfigSource config, int taskCount,
            FileOutputPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);

        // retryable (idempotent) output:
        return resume(task.dump(), taskCount, control);
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource,
            int taskCount,
            FileOutputPlugin.Control control)
    {
        control.run(taskSource);
        return Exec.newConfigDiff();
    }

    @Override
    public void cleanup(TaskSource taskSource,
            int taskCount,
            List<TaskReport> successTaskReports)
    {
    }

    @Override
    public TransactionalFileOutput open(TaskSource taskSource, final int taskIndex)
    {
        PluginTask task = taskSource.loadTask(PluginTask.class);

        List<String> cmdline = new ArrayList<String>();
        cmdline.addAll(buildShell());
        cmdline.add(task.getCommand());

        logger.info("Using command {}", cmdline);

        return new PluginFileOutput(cmdline, taskIndex);
    }

    @VisibleForTesting
    static List<String> buildShell()
    {
        String osName = System.getProperty("os.name");
        if(osName.indexOf("Windows") >= 0) {
            return ImmutableList.of("PowerShell.exe", "-Command");
        } else {
            return ImmutableList.of("sh", "-c");
        }
    }

    private static class ProcessWaitOutputStream
            extends FilterOutputStream
    {
        private Process process;

        public ProcessWaitOutputStream(OutputStream out, Process process)
        {
            super(out);
            this.process = process;
        }

        @Override
        public void close() throws IOException
        {
            super.close();
            waitFor();
        }

        private synchronized void waitFor() throws IOException
        {
            if (process != null) {
                int code;
                try {
                    code = process.waitFor();
                } catch (InterruptedException ex) {
                    throw Throwables.propagate(ex);
                }
                process = null;
                if (code != 0) {
                    throw new IOException(String.format(
                                "Command finished with non-zero exit code. Exit code is %d.", code));
                }
            }
        }
    }

    public class PluginFileOutput
            implements TransactionalFileOutput
    {
        private final List<String> cmdline;
        private final int taskIndex;
        private int seqId;
        private ProcessWaitOutputStream currentProcess;

        public PluginFileOutput(List<String> cmdline, int taskIndex)
        {
            this.cmdline = cmdline;
            this.taskIndex = taskIndex;
            this.seqId = 0;
            this.currentProcess = null;
        }

        public void nextFile()
        {
            closeCurrentProcess();
            Process proc = startProcess(cmdline, taskIndex, seqId);
            currentProcess = new ProcessWaitOutputStream(proc.getOutputStream(), proc);
            seqId++;
        }

        public void add(Buffer buffer)
        {
            try {
                currentProcess.write(buffer.array(), buffer.offset(), buffer.limit());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            } finally {
                buffer.release();
            }
        }

        public void finish()
        {
            closeCurrentProcess();
        }

        public void close()
        {
            closeCurrentProcess();
        }

        public void abort()
        {
        }

        public TaskReport commit()
        {
            return Exec.newTaskReport();
        }

        private void closeCurrentProcess()
        {
            try {
                if (currentProcess != null) {
                    currentProcess.close();
                    currentProcess = null;
                }
            } catch (IOException ex) {
                throw Throwables.propagate(ex);
            }
        }

        private Process startProcess(List<String> cmdline, int taskIndex, int seqId)
        {
            ProcessBuilder builder = new ProcessBuilder(cmdline.toArray(new String[cmdline.size()]))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builder.environment().put("INDEX", Integer.toString(taskIndex));
            builder.environment().put("SEQID", Integer.toString(seqId));
            // TODO transaction_time, etc

            try {
                return builder.start();
            } catch (IOException ex) {
                throw Throwables.propagate(ex);
            }
        }
    }
}
