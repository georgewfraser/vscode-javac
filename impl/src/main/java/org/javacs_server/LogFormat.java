package org.javacs_server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

class LogFormat extends Formatter {
    private final String format = "%1$tT.%1$tL\t%4$s\t%2$s\t%5$s%6$s%n";
    private final Date dat = new Date();

    @Override
    public synchronized String format(LogRecord record) {
        dat.setTime(record.getMillis());
        String source;
        if (record.getSourceClassName() != null) {
            source = record.getSourceClassName();
            if (record.getSourceMethodName() != null) {
                source += " " + record.getSourceMethodName();
            }
        } else {
            source = record.getLoggerName();
        }
        var message = formatMessage(record);
        var throwable = "";
        if (record.getThrown() != null) {
            var sw = new StringWriter();
            var pw = new PrintWriter(sw);
            pw.println();
            record.getThrown().printStackTrace(pw);
            pw.close();
            throwable = sw.toString();
        }
        return String.format(
                format, dat, source, record.getLoggerName(), record.getLevel().getLocalizedName(), message, throwable);
    }
}
