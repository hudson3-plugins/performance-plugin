package hudson.plugins.performance;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PerformancePublisher extends Recorder {
  @Extension
  public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
    @Override
    public String getDisplayName() {
      return Messages.Publisher_DisplayName();
    }

    @Override
    public String getHelpFile() {
      return "/plugin/performance/help.html";
    }

    public List<PerformanceReportParserDescriptor> getParserDescriptors() {
      return PerformanceReportParserDescriptor.all();
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }
  }

  private static final double THRESHOLD_TOLERANCE = 0.00000001;

  private int errorFailedThreshold = 0;
  private int errorUnstableThreshold = 0;

  private int performanceFailedThreshold = -1;
  private int performanceUnstableThreshold = -1;

  private int performanceTimeFailedThreshold = -1;
  private int performanceTimeUnstableThreshold = -1;

  /**
   * @deprecated as of 1.3. for compatibility
   */
  private transient String filename;

  /**
   * Configured report parseres.
   */
  private List<PerformanceReportParser> parsers;

  @DataBoundConstructor
  public PerformancePublisher(int errorFailedThreshold, int errorUnstableThreshold,
                              int performanceFailedThreshold, int performanceUnstableThreshold,
                              int performanceTimeFailedThreshold, int performanceTimeUnstableThreshold,
                              List<? extends PerformanceReportParser> parsers) {

    this.setErrorFailedThreshold(errorFailedThreshold);
    this.setErrorUnstableThreshold(errorUnstableThreshold);
    this.setPerformanceFailedThreshold(performanceFailedThreshold);
    this.setPerformanceUnstableThreshold(performanceUnstableThreshold);
    this.setPerformanceTimeFailedThreshold(performanceTimeFailedThreshold);
    this.setPerformanceTimeUnstableThreshold(performanceTimeUnstableThreshold);

    if (parsers == null) {
      parsers = Collections.emptyList();
    }
    this.parsers = new ArrayList<PerformanceReportParser>(parsers);
  }

  public static File getPerformanceReport(AbstractBuild<?, ?> build,
      String parserDisplayName, String performanceReportName) {
    return new File(build.getRootDir(),
        PerformanceReportMap.getPerformanceReportFileRelativePath(
            parserDisplayName,
            getPerformanceReportBuildFileName(performanceReportName)));
  }

  @Override
  public Action getProjectAction(AbstractProject<?, ?> project) {
    return new PerformanceProjectAction(project);
  }

  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.BUILD;
  }

  public List<PerformanceReportParser> getParsers() {
    return parsers;
  }

  /**
   * <p>
   * Delete the date suffix appended to the Performance result files by the
   * Maven Performance plugin
   * </p>
   * 
   * @param performanceReportWorkspaceName
   * @return the name of the PerformanceReport in the Build
   */
  public static String getPerformanceReportBuildFileName(
      String performanceReportWorkspaceName) {
    String result = performanceReportWorkspaceName;
    if (performanceReportWorkspaceName != null) {
      Pattern p = Pattern.compile("-[0-9]*\\.xml");
      Matcher matcher = p.matcher(performanceReportWorkspaceName);
      if (matcher.find()) {
        result = matcher.replaceAll(".xml");
      }
    }
    return result;
  }

  /**
   * look for performance reports based in the configured parameter includes.
   * 'includes' is - an Ant-style pattern - a list of files and folders
   * separated by the characters ;:,
   */
  protected static List<FilePath> locatePerformanceReports(FilePath workspace,
      String includes) throws IOException, InterruptedException {

    // First use ant-style pattern
    try {
      FilePath[] ret = workspace.list(includes);
      if (ret.length > 0) {
        return Arrays.asList(ret);
      }
    } catch (IOException e) {
    }

    // If it fails, do a legacy search
    ArrayList<FilePath> files = new ArrayList<FilePath>();
    String parts[] = includes.split("\\s*[;:,]+\\s*");
    for (String path : parts) {
      FilePath src = workspace.child(path);
      if (src.exists()) {
        if (src.isDirectory()) {
          files.addAll(Arrays.asList(src.list("**/*")));
        } else {
          files.add(src);
        }
      }
    }
    return files;
  }

  @Override
  public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
      BuildListener listener) throws InterruptedException, IOException {
    PrintStream logger = listener.getLogger();

    if (errorUnstableThreshold >= 0 && errorUnstableThreshold <= 100) {
      logger.println("Performance: Percentage of errors greater or equal than "
          + errorUnstableThreshold + "% sets the build as "
          + Result.UNSTABLE.toString().toLowerCase());
    } else {
      logger.println("Performance: No threshold configured for making the test "
          + Result.UNSTABLE.toString().toLowerCase());
    }
    if (errorFailedThreshold >= 0 && errorFailedThreshold <= 100) {
      logger.println("Performance: Percentage of errors greater or equal than "
          + errorFailedThreshold + "% sets the build as "
          + Result.FAILURE.toString().toLowerCase());
    } else {
      logger.println("Performance: No threshold configured for making the test "
          + Result.FAILURE.toString().toLowerCase());
    }

    // add the report to the build object.
    PerformanceBuildAction a = new PerformanceBuildAction(build, logger, parsers);
    build.addAction(a);

    for (PerformanceReportParser parser : parsers) {
      String glob = parser.glob;
      logger.println("Performance: Recording " + parser.getReportName() + " reports '" + glob + "'");

      List<FilePath> files = locatePerformanceReports(build.getWorkspace(), glob);

      if (files.isEmpty()) {
        if (build.getResult().isWorseThan(Result.UNSTABLE)) {
          return true;
        }
        build.setResult(Result.FAILURE);
        logger.println("Performance: no " + parser.getReportName() + " files matching '" + glob +
                       "' have been found. Has the report generated?. Setting Build to " + build.getResult());
        return true;
      }

      List<File> localReports = copyReportsToMaster(build, logger, files, parser.getDescriptor().getDisplayName());
      Collection<PerformanceReport> parsedReports = parser.parse(build, localReports, listener);
      Map<String, PerformanceReport> previousReports = loadPreviousReports(build);

      // mark the build as unstable or failure depending on the outcome.
      for (PerformanceReport r : parsedReports) {
        r.setBuildAction(a);
        PerformanceReport lastReport = previousReports.getOrDefault(r.getReportFileName(), null);
        if (lastReport != null) {
          r.setLastBuildReport(lastReport);
        }

        double errorPercent = r.errorPercent();
        double diffTime = r.getAverageDiff();
        double diffPercent = r.getAverageDiffPercent();

        Result result = Result.SUCCESS;
        if (isFailure(errorPercent, diffTime, diffPercent)) {
          result = Result.FAILURE;
          build.setResult(Result.FAILURE);
        } else if (isUnstable(errorPercent, diffTime, diffPercent)) {
          result = Result.UNSTABLE;
        }
        if (result.isWorseThan(build.getResult())) {
          build.setResult(result);
        }
        logger.println("Performance: File " + r.getReportFileName() + " reported " +
                       errorPercent + "% of errors, " +
                       diffPercent + "% (" + diffTime + " ms) in performance change " +
                       "[" + result + "]. Build status is: " + build.getResult());
      }
    }

    return true;
  }

  private Map<String, PerformanceReport> loadPreviousReports(AbstractBuild<?, ?> currentBuild) {

    AbstractBuild<?, ?> previousBuild = currentBuild.getPreviousBuild();
    if ( previousBuild == null ) {
      return Collections.emptyMap();
    }

    PerformanceBuildAction previousPerformanceAction = previousBuild.getAction(PerformanceBuildAction.class);
    if ( previousPerformanceAction == null ) {
      return Collections.emptyMap();
    }

    PerformanceReportMap previousPerformanceReportMap = previousPerformanceAction.getPerformanceReportMap();
    if (previousPerformanceReportMap == null) {
      return Collections.emptyMap();
    }

    return previousPerformanceReportMap.getPerformanceReportMap();
  }

  private boolean isFailure(double errorPercent, double diffTime, double diffPercent) {

    return (errorFailedThreshold >= 0 && errorFailedThreshold + THRESHOLD_TOLERANCE < errorPercent) ||
           (performanceFailedThreshold >= 0 && performanceFailedThreshold + THRESHOLD_TOLERANCE < diffPercent) &&
           (performanceTimeFailedThreshold >= 0 && performanceTimeFailedThreshold + THRESHOLD_TOLERANCE < diffTime);
  }

  private boolean isUnstable(double errorPercent, double diffTime, double diffPercent) {

    return (errorUnstableThreshold >= 0 && errorUnstableThreshold + THRESHOLD_TOLERANCE < errorPercent) ||
           (performanceUnstableThreshold >= 0 && performanceUnstableThreshold + THRESHOLD_TOLERANCE < diffPercent) &&
           (performanceTimeUnstableThreshold >= 0 && performanceTimeUnstableThreshold + THRESHOLD_TOLERANCE < diffTime);
  }

  private List<File> copyReportsToMaster(AbstractBuild<?, ?> build,
      PrintStream logger, List<FilePath> files, String parserDisplayName)
      throws IOException, InterruptedException {
    List<File> localReports = new ArrayList<File>();
    for (FilePath src : files) {
      final File localReport = getPerformanceReport(build, parserDisplayName,
          src.getName());
      if (src.isDirectory()) {
        logger.println("Performance: File '" + src.getName()
            + "' is a directory, not a Performance Report");
        continue;
      }
      src.copyTo(new FilePath(localReport));
      localReports.add(localReport);
    }
    return localReports;
  }

  public Object readResolve() {
    // data format migration
    if (parsers == null)
      parsers = new ArrayList<PerformanceReportParser>();
    if (filename != null) {
      parsers.add(new JMeterParser(filename));
      filename = null;
    }
    return this;
  }

  public int getErrorFailedThreshold() {
    return errorFailedThreshold;
  }

  public void setErrorFailedThreshold(int errorFailedThreshold) {
    this.errorFailedThreshold = Math.max(0, Math.min(errorFailedThreshold, 100));
  }

  public int getErrorUnstableThreshold() {
    return errorUnstableThreshold;
  }

  public void setErrorUnstableThreshold(int errorUnstableThreshold) {
    this.errorUnstableThreshold = Math.max(0, Math.min(errorUnstableThreshold,
        100));
  }

  public int getPerformanceFailedThreshold() {
    return performanceFailedThreshold;
  }

  public void setPerformanceFailedThreshold(int performanceFailedThreshold) {
    this.performanceFailedThreshold = Math.max(0, Math.min(performanceFailedThreshold, 100));
  }

  public int getPerformanceTimeFailedThreshold() {
    return performanceTimeFailedThreshold;
  }

  public void setPerformanceTimeFailedThreshold(int performanceTimeFailedThreshold) {
    this.performanceTimeFailedThreshold = performanceTimeFailedThreshold;
  }

  public int getPerformanceUnstableThreshold() {
    return performanceUnstableThreshold;
  }

  public void setPerformanceUnstableThreshold(int performanceUnstableThreshold) {
    this.performanceUnstableThreshold = Math.max(0, Math.min(performanceUnstableThreshold, 100));
  }

  public int getPerformanceTimeUnstableThreshold() {
    return performanceTimeUnstableThreshold;
  }

  public void setPerformanceTimeUnstableThreshold(int performanceTimeUnstableThreshold) {
    this.performanceTimeUnstableThreshold = performanceTimeUnstableThreshold;
  }

  public String getFilename() {
    return filename;
  }

  public void setFilename(String filename) {
    this.filename = filename;
  }
}
