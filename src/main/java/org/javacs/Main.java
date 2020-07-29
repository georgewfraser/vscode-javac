package org.javacs;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.FileHandler;
import org.javacs.lsp.*;

public class Main {
    private static final Logger LOG = Logger.getLogger("main");

    public static void setRootFormat() {
        var root = Logger.getLogger("");

        for (var h : root.getHandlers()) {
            h.setFormatter(new LogFormat());
        }
    }

    private static class CliArgs {

      public boolean quiet;
      public boolean help;
      public boolean file;

      private CliArgs(){
        quiet = false;
        help = false;
        file = false;
      }

      public static String help(){
        return "java-language-server [options]\n"
             + "\n"
             + "  --quiet             Suppress output\n"
             + "  --file              Logs to file\n"
             + "  --help              Shows this help message\n";
      }

      private static CliArgs parseCliArgs(String[] args){
        CliArgs parsed = new CliArgs();
        Arrays.stream(args).forEach(arg -> {
            if ("--quiet".equals(arg)) {
              parsed.quiet = true;
            } else if ("--file".equals(arg)) {
              parsed.file = true;
            } else if ("--help".equals(arg)) {
              parsed.help = true;
            } else {
              System.out.println("Unexpected argument '%s'".format(arg));
              System.out.println(help());
              System.exit(1);
            }
        });

        return parsed;
      }
    }

    public static void main(String[] args) {
      CliArgs parsed = CliArgs.parseCliArgs(args);

      if (parsed.help){
        System.out.println(CliArgs.help());
        System.exit(0);
      }

      try {
        if (parsed.file) {
          Logger.getLogger("").addHandler(new FileHandler("javacs.%u.log", false));
        } else if (parsed.quiet) {
          LOG.setLevel(Level.OFF);
        }
        setRootFormat();

        LSP.connect(JavaLanguageServer::new, System.in, System.out);
      } catch (Throwable t) {
        LOG.log(Level.SEVERE, t.getMessage(), t);

        System.exit(1);
      }
    }
}
