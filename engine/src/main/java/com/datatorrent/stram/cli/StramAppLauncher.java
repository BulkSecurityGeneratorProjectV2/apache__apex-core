/**
 * Copyright (c) 2012-2013 DataTorrent, Inc.
 * All rights reserved.
 */
package com.datatorrent.stram.cli;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.log4j.lf5.util.StreamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.api.DAG;
import com.datatorrent.api.StreamingApplication;
import com.datatorrent.api.annotation.ShipContainingJars;
import com.datatorrent.stram.DAGPropertiesBuilder;
import com.datatorrent.stram.StramClient;
import com.datatorrent.stram.StramLocalCluster;
import com.datatorrent.stram.StramUtils;
import com.datatorrent.stram.plan.logical.LogicalPlan;


/**
 * Launch a streaming application packaged as jar file
 * <p>
 * Parses the jar file for application resources (implementations of {@link StreamingApplication} or property files per
 * naming convention).<br>
 * Dependency resolution is based on the bundled pom.xml (if any) and the application is launched with a modified client
 * classpath that includes application dependencies so that classes defined in the DAG can be loaded and
 * {@link ShipContainingJars} annotations processed. Caching is performed for dependency classpath resolution.<br>
 * <br>
 *
 * @since 0.3.2
 */
public class StramAppLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(StramAppLauncher.class);

  final File jarFile;
  private Configuration conf;
  private final DAGPropertiesBuilder propertiesBuilder = new DAGPropertiesBuilder();
  private final List<AppFactory> appResourceList = new ArrayList<AppFactory>();
  private LinkedHashSet<URL> launchDependencies;

  /**
   *
   * Starts a command and waits for it to complete<p>
   * <br>
   *
   */
  public static class ProcessWatcher implements Runnable {

    private final Process p;
    private volatile boolean finished = false;
    private volatile int rc;

    @SuppressWarnings("CallToThreadStartDuringObjectConstruction")
    public ProcessWatcher(Process p) {
        this.p = p;
        new Thread(this).start();
    }

    public boolean isFinished() {
        return finished;
    }

    @Override
    public void run() {
        try {
            rc = p.waitFor();
        } catch (Exception e) {}
        finished = true;
    }
  }


  public static interface AppFactory {
      StreamingApplication createApp(Configuration conf);
      String getName();
  }

  public static class PropertyFileAppConfig implements AppFactory {
    final File propertyFile;

    public PropertyFileAppConfig(File file) {
      this.propertyFile = file;
    }

    @Override
    public StreamingApplication createApp(Configuration conf) {
      try {
        return DAGPropertiesBuilder.create(conf, propertyFile.getAbsolutePath());
      } catch (IOException e) {
        throw new IllegalArgumentException("Failed to load: " + this, e);
      }
    }

    @Override
    public String getName() {
      return propertyFile.getName();
    }

  }


  public StramAppLauncher(File appJarFile) throws Exception {
    this(appJarFile, null);
  }

  public StramAppLauncher(File appJarFile, Configuration conf) throws Exception {
    this.jarFile = appJarFile;
    this.conf = conf;
    init();
  }

    public StramAppLauncher(FileSystem fs, Path path) throws Exception {
      this(fs, path, null);
    }

  public StramAppLauncher(FileSystem fs, Path path, Configuration conf) throws Exception {
    File jarsDir = new File(StramClientUtils.getSettingsRootDir(), "jars");
    jarsDir.mkdirs();
    File localJarFile = new File(jarsDir, path.getName());
    fs.copyToLocalFile(path, new Path(localJarFile.getAbsolutePath()));
    this.jarFile = localJarFile;
    this.conf = conf;
    init();
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  private void init() throws Exception {

    if (conf == null) {
      conf = getConfig(null, null);
    }
    propertiesBuilder.addFromConfiguration(conf);

    File baseDir =  StramClientUtils.getSettingsRootDir();
    baseDir = new File(baseDir, jarFile.getName());
    baseDir.mkdirs();

    File pomCrcFile = new File(baseDir, "pom.xml.crc");
    File cpFile = new File(baseDir, "mvn-classpath");
    long pomCrc = 0;
    String cp = null;

    // read crc and classpath file, if it exists
    // (we won't run mvn again if pom didn't change)
    if (cpFile.exists()) {
      try {
       DataInputStream dis = new DataInputStream(new FileInputStream(pomCrcFile));
       pomCrc = dis.readLong();
       dis.close();
       cp = FileUtils.readFileToString(cpFile, "UTF-8");
      } catch (Exception e) {
        LOG.error("Cannot read CRC from {}", pomCrcFile);
      }
    }

 // TODO: cache based on application jar checksum
    FileUtils.deleteDirectory(baseDir);

    java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile);
    List<String> classFileNames = new ArrayList<String>();

    java.util.Enumeration<JarEntry> entriesEnum = jar.entries();
    while (entriesEnum.hasMoreElements()) {
        java.util.jar.JarEntry jarEntry = entriesEnum.nextElement();
        if (!jarEntry.isDirectory()) {
          if (jarEntry.getName().endsWith("pom.xml")) {
            File pomDst = new File(baseDir, "pom.xml");
            FileUtils.copyInputStreamToFile(jar.getInputStream(jarEntry), pomDst);
            if (pomCrc != jarEntry.getCrc()) {
              LOG.info("CRC of " + jarEntry.getName() + " changed, invalidating cached classpath.");
              cp = null;
              pomCrc = jarEntry.getCrc();
            }
          } else if (jarEntry.getName().endsWith(".app.properties")) {
            File targetFile = new File(baseDir, jarEntry.getName());
            FileUtils.copyInputStreamToFile(jar.getInputStream(jarEntry), targetFile);
            appResourceList.add(new PropertyFileAppConfig(targetFile));
          } else if (jarEntry.getName().endsWith(".class")) {
            classFileNames.add(jarEntry.getName());
          }
        }
    }
    jar.close();

    File pomFile = new File(baseDir, "pom.xml");
    if (pomFile.exists()) {
      if (cp == null) {
        // try to generate dependency classpath
        LOG.info("Generating classpath via mvn from " + pomFile);
        LOG.info("java.home: " + System.getProperty("java.home"));

        String malhar_home;
        if (StramClientUtils.MALHAR_HOME != null && !StramClientUtils.MALHAR_HOME.isEmpty()) {
          malhar_home = " -Duser.home=" + StramClientUtils.MALHAR_HOME;
        }
        else {
          malhar_home = "";
        }
        String cmd = "mvn dependency:build-classpath" + malhar_home + " -Dmdep.outputFile=" + cpFile.getAbsolutePath() + " -f " + pomFile;

        Process p = Runtime.getRuntime().exec(cmd);
        ProcessWatcher pw = new ProcessWatcher(p);
        InputStream output = p.getInputStream();
        while(!pw.isFinished()) {
            StreamUtils.copy(output, System.out);
        }
        if (pw.rc != 0) {
          throw new RuntimeException("Failed to run: " + cmd + " (exit code " + pw.rc + ")");
        }
        cp = FileUtils.readFileToString(cpFile);
      }
      DataOutputStream dos = new DataOutputStream(new FileOutputStream(pomCrcFile));
      dos.writeLong(pomCrc);
      dos.close();
      FileUtils.writeStringToFile(cpFile, cp, false);
    }

    LinkedHashSet<URL> clUrls = new LinkedHashSet<URL>();

//    // dependencies from parent loader
//    ClassLoader baseCl = StramAppLauncher.class.getClassLoader();
//    if (baseCl instanceof URLClassLoader) {
//      URL[] baseUrls = ((URLClassLoader)baseCl).getURLs();
//      // launch class path takes precedence - add first
//      clUrls.addAll(Arrays.asList(baseUrls));
//    }

    clUrls.add(new URL("jar", "","file:" + jarFile.getAbsolutePath()+"!/"));
    // add the jar dependencies
    if (cp != null) {
      String[] pathList = org.apache.commons.lang.StringUtils.splitByWholeSeparator(cp, ":");
      for (String path : pathList) {
        clUrls.add(new URL("file:" + path));
      }
    }

    for (URL baseURL : clUrls) {
      LOG.debug("Dependency: {}", baseURL);
    }

    this.launchDependencies = clUrls;

    // we have the classpath dependencies, scan for java configurations
    findAppConfigClasses(classFileNames);

  }

  /**
   * Scan the application jar file entries for configuration classes.
   * This needs to occur in a class loader with access to the application dependencies.
   */
  private void findAppConfigClasses(List<String> classFileNames) {
    URLClassLoader cl = URLClassLoader.newInstance(launchDependencies.toArray(new URL[launchDependencies.size()]));
    for (final String classFileName : classFileNames) {
      final String className = classFileName.replace('/', '.').substring(0, classFileName.length() - 6);
      try {
        Class<?> clazz = cl.loadClass(className);
        if (StreamingApplication.class.isAssignableFrom(clazz)) {
          final AppFactory appConfig = new AppFactory() {

            @Override
            public String getName() {
              return classFileName;
            }

            @Override
            public StreamingApplication createApp(Configuration conf) {
              // load class from current context class loader
              Class<? extends StreamingApplication> c = StramUtils.classForName(className, StreamingApplication.class);
              return StramUtils.newInstance(c);
            }
          };
          appResourceList.add(appConfig);
        }
      } catch (Throwable e) { // java.lang.NoClassDefFoundError
        LOG.error("Unable to load class: " + className + " " + e);
      }
    }
  }

  public static Configuration getConfig(String overrideConfFileName, Map<String, String> overrideProperties) throws IOException
  {
    Configuration conf = new Configuration(false);
    StramClientUtils.addStramResources(conf);
    /*
    // user settings
    File cfgResource = new File(StramClientUtils.getSettingsRootDir(), StramClientUtils.STRAM_SITE_XML_FILE);
    if (cfgResource.exists()) {
      LOG.info("Loading settings: " + cfgResource.toURI());
      conf.addResource(new Path(cfgResource.toURI()));
    }
    */
    //File appDir = new File(StramClientUtils.getSettingsRootDir(), jarFile.getName());
    //cfgResource = new File(appDir, StramClientUtils.STRAM_SITE_XML_FILE);
    //if (cfgResource.exists()) {
    //  LOG.info("Loading settings from: " + cfgResource.toURI());
    //  conf.addResource(new Path(cfgResource.toURI()));
    //}
    if (overrideConfFileName != null) {
      File overrideConfFile = new File(overrideConfFileName);
      if (overrideConfFile.exists()) {
        LOG.info("Loading settings: " + overrideConfFile.toURI());
        conf.addResource(new Path(overrideConfFile.toURI()));
      } else {
        throw new IOException("Problem opening file " + overrideConfFile);
      }
    }
    if (overrideProperties != null) {
      for (Map.Entry<String, String> entry : overrideProperties.entrySet()) {
        conf.set(entry.getKey(), entry.getValue());
      }
    }
    return conf;
  }

  public Map<String, String> getAppAliases() {
    return propertiesBuilder.getAppAliases();
  }

  public LogicalPlan prepareDAG(AppFactory appConfig) {
    LogicalPlan dag = new LogicalPlan();
    StreamingApplication app = appConfig.createApp(conf);
    propertiesBuilder.prepareDAG(dag, app, appConfig.getName(), conf);
    return dag;
  }

  /**
   * Run application in-process. Returns only once application completes.
   * @param appConfig
   * @param config
   * @throws Exception
   */
  public void runLocal(AppFactory appConfig) throws Exception {
    // local mode requires custom classes to be resolved through the context class loader
    loadDependencies();
    conf.set(DAG.LAUNCH_MODE, StreamingApplication.LAUNCHMODE_LOCAL);
    StramLocalCluster lc = new StramLocalCluster(prepareDAG(appConfig));
    lc.run();
  }

  public URLClassLoader loadDependencies() {
    URLClassLoader cl = URLClassLoader.newInstance(launchDependencies.toArray(new URL[launchDependencies.size()]));
    Thread.currentThread().setContextClassLoader(cl);
    return cl;
  }

  /**
   * Submit application to the cluster and return the app id.
   * Sets the context class loader for application dependencies.
   * @param appConfig
   * @return ApplicationId
   * @throws Exception
   */
  public ApplicationId launchApp(AppFactory appConfig) throws Exception {

    loadDependencies();
    conf.set(DAG.LAUNCH_MODE, StreamingApplication.LAUNCHMODE_YARN);
    LogicalPlan dag = prepareDAG(appConfig);
    StramClient client = new StramClient(dag);
    client.startApplication();
    return client.getApplicationReport().getApplicationId();
  }

  public List<AppFactory> getBundledTopologies() {
    return Collections.unmodifiableList(this.appResourceList);
  }

}
