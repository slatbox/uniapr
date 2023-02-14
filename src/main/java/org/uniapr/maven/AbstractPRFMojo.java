/*
 * Copyright (C) Illinois - All Rights Reserved.
 * Unauthorized copying of this file via any medium is
 * strictly prohibited.
 * This code base is proprietary and confidential.
 * Designed by the UniAPR team.
 */
package org.uniapr.maven;

import org.uniapr.PRFEntryPoint;
import org.uniapr.PatchGenerationPlugin;
import org.uniapr.PatchGenerationPluginInfo;
import org.uniapr.commons.misc.MemberNameUtils;
import org.uniapr.jvm.offline.LeakingFieldMain;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.pitest.classinfo.CachingByteArraySource;
import org.pitest.classinfo.ClassByteArraySource;
import org.pitest.classpath.ClassPath;
import org.pitest.classpath.ClassPathByteArraySource;
import org.pitest.classpath.ClassloaderByteArraySource;
import org.pitest.functional.Option;
import org.reflections.Reflections;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Ali Ghanbari (ali.ghanbari@utdallas.edu)
 */
public abstract class AbstractPRFMojo extends AbstractMojo {
    private static final int CACHE_SIZE = 200;

    protected File compatibleJREHome;

    protected boolean inferFailingTests;

    private PatchGenerationPlugin patchGenerationPluginImpl;

    @Parameter(property = "project", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "plugin.artifactMap", readonly = true, required = true)
    protected Map<String, Artifact> pluginArtifactMap;

    // -----------------------
    // ---- plugin params ----
    // -----------------------

    /**
     * A list of failing test cases: a patch is considered plausible if it
     * does not introduce regression and passes all these failing tests
     *
     * If you leave this parameter unspecified, PRF shall infer failing test
     * names automatically, but using this feature is not recommended for
     * Defects4J programs as reproducing failing tests in those programs
     * is difficult.
     */
    @Parameter(property = "failingTests")
    protected List<String> failingTests;

    /**
     * This parameter is used in discovering application classes. A class shall
     * be considered application class if its full name starts with
     * <code>whiteListPrefix</code>
     */
    @Parameter(property = "whiteListPrefix", defaultValue = "${project.groupId}")
    protected String whiteListPrefix;

    /**
     * A timeout bias of 1000 means that we have to wait at least 1 second to
     * decide whether or not we a test case is going to time out
     */
    @Parameter(property = "timeoutBias", defaultValue = "2000")
    protected long timeoutBias;

    /**
     * A timeout coefficient of 0.5 means that a patch running more than
     * 1.5 times of its original time will be deemed timed out
     */
    @Parameter(property = "timeoutCoefficient", defaultValue = "0.5")
    protected double timeoutCoefficient;

    /**
     * We expect one folder for each patch. All class files inside each
     * folder shall be considered as part of the patch.
     */
    @Parameter(property = "patchesPool", defaultValue = "patches-pool")
    protected File patchesPool;

    
    /**
     * A parameter for controlling whether to perform JVM-reset at all
     */
    @Parameter(property = "resetJVM", defaultValue = "false")
    protected boolean resetJVM;

    /**
     * A parameter for controlling whether to start a new JVM for each patch
     */
    @Parameter(property = "restartJVM", defaultValue = "false")
    protected boolean restartJVM;

	/**
	 * A parameter for controlling whether to monitor and reset interface clinit.
	 */
    @Parameter(property = "resetInterface", defaultValue = "false")
    protected boolean resetInterface;

	/**
	 * A parameter for controlling whether to print out detailed test failure message.
	 */
    @Parameter(property = "debug", defaultValue = "false")
    protected boolean debug;
    
    /**
     * The name of patch generation plugin.
     * Example:
     * <patchGenerationPlugin>
     *     <name>capgen</name>
     *     <compatibleJDKHOME>/for/example/jdk8</compatibleJDKHOME>
     *     <params>
     *         <jdk7Loc>/path/to/jdk7Home/bin/</jdk7Loc>
     *         <project>Closure</project>
     *         <bugId>112</bugId>
     *     </params>
     * </patchGenerationPlugin>
     * As for the name of the plugin, case does not matter
     * The order of parameters does not matter
     */
    @Parameter(property = "patchGenerationPlugin")
    protected PatchGenerationPluginInfo patchGenerationPlugin;

    /**
     * The "all_tests" file generated by command `defects4j test`.
     * This parameter forces uniapr to execute the same tests as defects4j.
     */
    @Parameter(property = "d4jAllTestsFile")
    protected File d4jAllTestsFile;

    /**
     * A parameter to add JVM arguments to the subprocess (delimited by semicolon).
     */
    @Parameter(property = "argLine")
    protected String argLine;

    /**
     * A parameter to make uniapr only run profiler, i.e., no patch validation.
     */
    @Parameter(property = "profilerOnly", defaultValue = "false")
    protected boolean profilerOnly;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        validateAndSanitizeParameters();

        final ClassPath classPath = createClassPath();
        final ClassByteArraySource byteArraySource = createClassByteArraySource(classPath);

        // Compute the potential static fields that may pollute JVM
        // provided that JVM reset is enabled
        if(this.resetJVM) {
            LeakingFieldMain.invoke(obtainCompleteCP());
        }
        
        try {
            PRFEntryPoint.createEntryPoint()
                    .withClassPath(classPath)
                    .withByteArraySource(byteArraySource)
                    .withWhiteListPrefix(this.whiteListPrefix)
                    .withFailingTests(this.failingTests)
                    .withCompatibleJREHome(this.compatibleJREHome)
                    .withTimeoutBias(this.timeoutBias)
                    .withTimeoutCoefficient(this.timeoutCoefficient)
                    .withPatchesPool(this.patchesPool)
                    .withPatchGenerationPlugin(this.patchGenerationPluginImpl)
                    .withResetJVM(!this.restartJVM && this.resetJVM)  // make no sense to reset JVM if restartJVM
                    .withRestartJVM(this.restartJVM)
                    .withResetInterface(this.resetInterface)
                    .withDebug(this.debug)
                    .withD4jAllTestsFile(d4jAllTestsFile)
                    .withArgLine(argLine)
                    .withProfilerOnly(profilerOnly)
                    .run(this.patchGenerationPlugin);
        } catch (Exception e) {
            e.printStackTrace();
            throw new MojoFailureException(e.getMessage());
        }
    }

    private ClassPath createClassPath() {
        final List<File> classPathElements = new ArrayList<>();
        classPathElements.addAll(getProjectClassPath());
        classPathElements.addAll(getPluginClassPath());
        return new ClassPath(classPathElements);
    }

    private List<File> getProjectClassPath() {
        final List<File> classPath = new ArrayList<>();
        try {
            for (final Object cpElement : this.project.getTestClasspathElements()) {
                classPath.add(new File((String) cpElement));
            }
        } catch (DependencyResolutionRequiredException e) {
            getLog().warn(e);
        }
        return classPath;
    }

    private void validateAndSanitizeParameters() throws MojoFailureException {
        final String jreHome = System.getProperty("java.home");
        if (jreHome == null) {
            throw new MojoFailureException("JAVA_HOME is not set");
        }
        this.compatibleJREHome = new File(jreHome);
        if (!this.compatibleJREHome.isDirectory()) {
            throw new MojoFailureException("Invalid JAVA_HOME");
        }

        if (this.whiteListPrefix.isEmpty()) {
            getLog().warn("Missing whiteListPrefix");
            this.whiteListPrefix = this.project.getGroupId();
            getLog().info("Using " + this.whiteListPrefix + " as whiteListPrefix");
        }

        if (this.timeoutBias < 0L) {
            throw new MojoFailureException("Invalid timeout bias");
        }

        if (this.timeoutBias < 1000L) {
            getLog().warn("Too small timeout bias");
        }

        if (this.timeoutCoefficient < 0.D) {
            throw new MojoFailureException("Invalid timeout coefficient");
        }

        this.inferFailingTests =
                (this.failingTests == null || this.failingTests.isEmpty());

        if (!this.inferFailingTests) {
            final List<String> failingTests = new LinkedList<>();
            for (final String testName : this.failingTests) {
                failingTests.add(MemberNameUtils.sanitizeTestName(testName));
            }
            this.failingTests = failingTests;
        }

        this.patchGenerationPluginImpl = null;
        if (this.patchGenerationPlugin.getName() != null) {
            this.patchGenerationPluginImpl = findPatchGenerationPlugin();
            if (patchGenerationPluginImpl == null) {
                throw new MojoFailureException("No plugin with the name "
                        + this.patchGenerationPlugin + " found in classpath." +
                        " This is perhaps a classpath issue.");
            }
            getLog().info("Found Patch Generation Plugin: " + this.patchGenerationPluginImpl.name()
                    + " (" + this.patchGenerationPluginImpl.description() + ")");
        }
    }

    private PatchGenerationPlugin findPatchGenerationPlugin() {
        Reflections reflections = new Reflections(Thread.currentThread().getContextClassLoader());

        for (final Class<? extends PatchGenerationPlugin> pluginClass :
                reflections.getSubTypesOf(PatchGenerationPlugin.class)) {
            final PatchGenerationPlugin plugin;
            try {
                plugin = pluginClass.newInstance();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
            if (this.patchGenerationPlugin.matches(plugin)) {
                return plugin;
            }
        }
        return null;
    }

    private List<File> getPluginClassPath() {
        final List<File> classPath = new ArrayList<>();
        for (Object artifact : this.pluginArtifactMap.values()) {
            final Artifact dependency = (Artifact) artifact;
            if (isRelevantDep(dependency)) {
                classPath.add(dependency.getFile());
            }
        }
        return classPath;
    }

    private boolean isRelevantDep(final Artifact dependency) {
        return dependency.getGroupId().equals("org.uniapr")
                && dependency.getArtifactId().equals("uniapr-plugin");
    }

    private ClassByteArraySource createClassByteArraySource(final ClassPath classPath) {
        final ClassPathByteArraySource cpbas = new ClassPathByteArraySource(classPath);
        final ClassByteArraySource cbas = fallbackToClassLoader(cpbas);
        return new CachingByteArraySource(cbas, CACHE_SIZE);
    }

    // this method is adopted from PIT's source code
    private ClassByteArraySource fallbackToClassLoader(final ClassByteArraySource bas) {
        final ClassByteArraySource clSource = ClassloaderByteArraySource.fromContext();
        return new ClassByteArraySource() {
            @Override
            public Option<byte[]> getBytes(String clazz) {
                final Option<byte[]> maybeBytes = bas.getBytes(clazz);
                if (maybeBytes.hasSome()) {
                    return maybeBytes;
                }
                return clSource.getBytes(clazz);
            }
        };
    }
    
    public String obtainCompleteCP(){
    	System.out.println("!!cp: start!!");
    	String cp=this.project.getBuild().getOutputDirectory()+":"+this.project.getBuild().getTestOutputDirectory();
    	List<String> classPathElements = new ArrayList<String>();
		Set<Artifact> dependencies = this.project.getArtifacts();
		for (Artifact dependency : dependencies) {
			cp+=":"+dependency.getFile().getAbsolutePath();
		}
		System.out.println("!!cp: "+cp);
    	return cp;
    }
}
