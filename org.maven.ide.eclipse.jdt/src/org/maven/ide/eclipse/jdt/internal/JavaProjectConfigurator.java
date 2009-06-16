/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.jdt.internal;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.environments.IExecutionEnvironment;
import org.eclipse.jdt.launching.environments.IExecutionEnvironmentsManager;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

import org.maven.ide.eclipse.MavenPlugin;
import org.maven.ide.eclipse.jdt.BuildPathManager;
import org.maven.ide.eclipse.jdt.IClasspathDescriptor;
import org.maven.ide.eclipse.jdt.IClasspathEntryDescriptor;
import org.maven.ide.eclipse.jdt.IJavaProjectConfigurator;
import org.maven.ide.eclipse.project.IMavenProjectFacade;
import org.maven.ide.eclipse.project.IMavenProjectVisitor;
import org.maven.ide.eclipse.project.IProjectConfigurationManager;
import org.maven.ide.eclipse.project.configurator.AbstractProjectConfigurator;
import org.maven.ide.eclipse.project.configurator.ILifecycleMapping;
import org.maven.ide.eclipse.project.configurator.ProjectConfigurationRequest;
import org.maven.ide.eclipse.util.Util;


/**
 * DefaultJavaConfigurator
 * 
 * @author igor
 */
public class JavaProjectConfigurator extends AbstractProjectConfigurator {

  private static final List<String> SOURCES = Arrays.asList("1.1,1.2,1.3,1.4,1.5,1.6,1.7".split(","));

  private static final List<String> TARGETS = Arrays.asList("1.1,1.2,1.3,1.4,jsr14,1.5,1.6,1.7".split(","));

  private static final LinkedHashMap<String, String> ENVIRONMENTS = new LinkedHashMap<String, String>();
  static {
    ENVIRONMENTS.put("1.1", "JRE-1.1");
    ENVIRONMENTS.put("1.2", "J2SE-1.2");
    ENVIRONMENTS.put("1.3", "J2SE-1.3");
    ENVIRONMENTS.put("1.4", "J2SE-1.4");
    ENVIRONMENTS.put("1.5", "J2SE-1.5");
    ENVIRONMENTS.put("jsr14", "J2SE-1.5");
    ENVIRONMENTS.put("1.6", "JavaSE-1.6");
    ENVIRONMENTS.put("1.7", "JavaSE-1.7");
  }

  private static final String DEFAULT_COMPILER_LEVEL = "1.4";

  public void configure(ProjectConfigurationRequest request, final IProgressMonitor monitor) throws CoreException {

    IProject project = request.getProject();
    MavenSession mavenSession = request.getMavenSession();
    IMavenProjectFacade facade = request.getMavenProjectFacade();

    addNature(project, JavaCore.NATURE_ID, monitor);

    IJavaProject javaProject = JavaCore.create(project);

    // XXX full/incremental configuration update
    final IClasspathDescriptor classpath = new ClasspathDescriptor(javaProject);

    // source/target compiler compliance levels
    String source = null, target = null;
    for(MojoExecution mojoExecution : facade.getExecutionPlan(monitor).getExecutions()) {
      if(isJavaCompilerExecution(mojoExecution)) {
        source = getCompilerLevel(mavenSession, mojoExecution, "source", source, SOURCES);
        target = getCompilerLevel(mavenSession, mojoExecution, "target", target, TARGETS);
      }
    }

    if (source == null || target == null) {
      // this really means java compiler is not present in build lifecycle
      throw new IllegalArgumentException("Not a java project " + project);
    }

    // source folders
    facade.accept(new IMavenProjectVisitor() {
      public boolean visit(IMavenProjectFacade projectFacade) throws CoreException {
        addProjectSourceFolders(classpath, projectFacade, projectFacade.getMavenProject(monitor));
        return true;
      }
    }, IMavenProjectVisitor.NESTED_MODULES);

    IProjectConfigurationManager configurationManager = MavenPlugin.getDefault().getProjectConfigurationManager();
    ILifecycleMapping lifecycleMapping = configurationManager.getLifecycleMapping(facade, monitor);
    for (AbstractProjectConfigurator configurator : lifecycleMapping.getProjectConfigurators(facade, monitor)) {
      if (configurator instanceof IJavaProjectConfigurator) {
        ((IJavaProjectConfigurator) configurator).configureRawClasspath(request, classpath, monitor);
      }
    }

    // classpath containers
    addJREClasspathContainer(classpath, target);
    addMavenClasspathContainer(classpath);

    // set java project options
    Map<String, String> options = javaProject.getOptions(false);
    options.put(JavaCore.COMPILER_SOURCE, source);
    options.put(JavaCore.COMPILER_COMPLIANCE, source);
    options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, target);
    javaProject.setOptions(options);

    javaProject.setRawClasspath(classpath.getEntries(), facade.getOutputLocation(), monitor);
  }


  void addProjectSourceFolders(IClasspathDescriptor classpath, IMavenProjectFacade projectFacade, MavenProject mavenProject) throws CoreException {
    IProject project = projectFacade.getProject();
    IWorkspaceRoot workspaceRoot = project.getWorkspace().getRoot();

    IFolder classes = workspaceRoot.getFolder(projectFacade.getOutputLocation());
    IFolder testClasses = workspaceRoot.getFolder(projectFacade.getTestOutputLocation());

    Util.createFolder(classes, true);
    Util.createFolder(testClasses, true);

    addSourceDirs(classpath, projectFacade, mavenProject.getCompileSourceRoots(), classes.getFullPath());
    addResourceDirs(classpath, projectFacade, mavenProject.getBuild().getResources(), classes.getFullPath());

    addSourceDirs(classpath, projectFacade, mavenProject.getTestCompileSourceRoots(), testClasses.getFullPath());
    addResourceDirs(classpath, projectFacade, mavenProject.getBuild().getTestResources(), testClasses.getFullPath());
  }

  private void addResourceDirs(IClasspathDescriptor classpath, IMavenProjectFacade projectFacade,
      List<Resource> resources, IPath outputPath) throws CoreException {
    IProject project = projectFacade.getProject();
    
    for(Resource resource : resources) {
      File resourceDirectory = new File(resource.getDirectory());
      if(resourceDirectory.exists() && resourceDirectory.isDirectory()) {
        IPath relativePath = projectFacade.getProjectRelativePath(resource.getDirectory());
        IResource r = project.findMember(relativePath);
        if(r == project) {
          /* 
           * Workaround for the Java Model Exception: 
           *   Cannot nest output folder 'xxx/src/main/resources' inside output folder 'xxx'
           * when pom.xml have something like this:
           * 
           * <build>
           *   <resources>
           *     <resource>
           *       <directory>${basedir}</directory>
           *       <targetPath>META-INF</targetPath>
           *       <includes>
           *         <include>LICENSE</include>
           *       </includes>
           *     </resource>
           */
          console.logError("Skipping resource folder " + r.getFullPath());
        } else if(r != null && classpath.containsPath(r.getFullPath())) {
          console.logMessage("Adding resource folder " + r.getFullPath());
//          IClasspathEntry cpe = JavaCore.newSourceEntry(, , outputPath);
          classpath.addSourceEntry(r.getFullPath(), outputPath, new IPath[] {new Path("**")} /*exclusion*/, new IPath[0] /*inclusions*/, false /*optional*/);
        }
      }
    }
  }


  private void addSourceDirs(IClasspathDescriptor classpath, IMavenProjectFacade projectFacade, List<String> sourceRoots, IPath outputPath) throws CoreException {
    IProject project = projectFacade.getProject();
    for(String sourceRoot : sourceRoots) {
      IFolder sourceFolder = project.getFolder(projectFacade.getProjectRelativePath(sourceRoot));

      if(sourceFolder != null && sourceFolder.exists()) {
        console.logMessage("Adding source folder " + sourceFolder.getFullPath());
        classpath.addSourceEntry(sourceFolder.getFullPath(), outputPath, false);
      } else {
        if (sourceFolder != null) {
          classpath.removeEntry(sourceFolder.getFullPath());
        }
      }
    }
  }


  private String getCompilerLevel(MavenSession mavenSession, MojoExecution mojoExecution, String parameter,
      String source, List<String> levels) throws CoreException {
    int levelIdx = getLevelIndex(source, levels);

    source = getParameterValue(mavenSession, mojoExecution, parameter, String.class);

    int newLevelIdx = getLevelIndex(source, levels);

    if (newLevelIdx > levelIdx) {
      levelIdx = newLevelIdx;
    }

    if (levelIdx < 0) {
      return DEFAULT_COMPILER_LEVEL;
    }

    return levels.get(levelIdx);
  }

  private int getLevelIndex(String level, List<String> levels) {
    return level != null? levels.indexOf(level): -1;
  }

  private boolean isJavaCompilerExecution(MojoExecution mojoExecution) {
    return "org.apache.maven.plugins".equals(mojoExecution.getGroupId()) && "maven-compiler-plugin".equals(mojoExecution.getArtifactId());
  }

  private void addJREClasspathContainer(IClasspathDescriptor classpath, String target) {
    // remove existing JRE entry
    classpath.removeEntry(new ClasspathDescriptor.EntryFilter() {
      public boolean accept(IClasspathEntryDescriptor descriptor) {
        return JavaRuntime.JRE_CONTAINER.equals(descriptor.getPath().segment(0));
      }
    });

    IClasspathEntry cpe;
    IExecutionEnvironment executionEnvironment = getExecutionEnvironment(ENVIRONMENTS.get(target));
    if(executionEnvironment == null) {
      cpe = JavaRuntime.getDefaultJREContainerEntry();
    } else {
      IPath containerPath = JavaRuntime.newJREContainerPath(executionEnvironment);
      cpe = JavaCore.newContainerEntry(containerPath);
    }

    classpath.addEntry(cpe);
  }

  private IExecutionEnvironment getExecutionEnvironment(String environmentId) {
    IExecutionEnvironmentsManager manager = JavaRuntime.getExecutionEnvironmentsManager();
    for(IExecutionEnvironment environment : manager.getExecutionEnvironments()) {
      if(environment.getId().equals(environmentId)) {
        return environment;
      }
    }
    return null;
  }
  
  private void addMavenClasspathContainer(IClasspathDescriptor classpath) {
    // remove any old maven classpath container entries
    classpath.removeEntry(new ClasspathDescriptor.EntryFilter() {
      public boolean accept(IClasspathEntryDescriptor entry) {
        return BuildPathManager.isMaven2ClasspathContainer(entry.getPath());
      }
    });

    // add new entry
    IClasspathEntry cpe = BuildPathManager.getDefaultContainerEntry();
    classpath.addEntry(cpe);
  }

}
