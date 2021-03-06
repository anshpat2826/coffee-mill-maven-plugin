/*
 * Copyright 2013 OW2 Nanoko Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nanoko.coffee.mill.mojos.others;

import org.apache.commons.vfs2.*;
import org.apache.commons.vfs2.impl.DefaultFileMonitor;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.nanoko.coffee.mill.mojos.AbstractCoffeeMillMojo;
import org.nanoko.coffee.mill.processors.*;
import org.nanoko.coffee.mill.utils.OptionsHelper;
import org.nanoko.coffee.mill.utils.ReactorUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This mojo watches the file change in the source directories and process them automatically.
 * To work correctly, launch <tt>mvn clean test</tt> first. This will resolve and prepare all required file.
 * Then <tt>mvn org.nanoko.coffee-mill:coffee-mill-maven-plugin:watch</tt> will starts the <i>watch</i> mode.
 *
 * This mojo supports reactor mode, i.e. is able to watch several modules and updates files. To enable this mode,
 * launch the watch mode with <tt>-Dwatched.project=artifactId of the final project</tt>. This will watch all
 * resources of all the modules of the reactor and copy the resulting artifact on the other module in the specified
 * project.
 *
 * You can configure the watched port with the <tt>-Dwatch.port=8234</tt> option. By default the used port is 8234.
 * @goal watch
 */
public class WatchMojo extends AbstractCoffeeMillMojo implements FileListener {

    /**
     * The maven session.
     *
     * @parameter expression="${session}"
     * @required
     * @readonly
     */
    protected MavenSession session;

    /**
     * Contains the full list of projects in the reactor.
     *
     * @parameter expression="${reactorProjects}"
     * @readonly
     */
    protected List<MavenProject> reactorProjects;


    /**
     * @parameter default-value="true"
     */
    protected boolean watchCoffeeScript;

    /**
     * @parameter default-value="true"
     */
    protected boolean watchLess;

    /**
     * @parameter default-value="true"
     */
    protected boolean watchDust;

    /**
     * @parameter default-value="true"
     */
    protected boolean watchDoAggregate;

    /**
     * @parameter default-value="false"
     */
    protected boolean watchValidateJS;

    /**
     * @parameter default-value="false"
     */
    protected boolean watchValidateCSS;

    /**
     * Enables the PNG and JPEG optimization
     * @parameter default-value="true"
     */
    protected boolean watchOptimizeAssets;

    /**
     * @parameter default-value="true"
     */
    protected boolean watchRunServer;

    /**
     * @parameter default-value="8234" expression="${watch.port}"
     */
    protected int watchJettyServerPort;

    /**
     * @parameter expression="${watched.project}" default-value="${project.artifactId}"
     */
    protected String watchedProject;

    /**
     * @parameter
     */
    List<String> javascriptAggregation;

    /**
     * @parameter
     */
    protected List<String> cssAggregation;

    /**
     * The Jetty Server
     */
    protected Server server;
    /**
     * The processors
     */
    protected List<Processor> processors;

    /**
     * @parameter default-value=2
     */
    protected int optiPngOptimizationLevel;



    public void execute() throws MojoExecutionException, MojoFailureException {
        // Are we in reactor mode, if so are we the target project
        if (! watchedProject.equals(project.getArtifactId())) {
            getLog().debug("Not the watched project, skip");
            if ("js".equals(project.getPackaging())) {
                getLog().debug("Adding the current project to session " + watchedProject);
                ReactorUtils.addWatcherToSession(this, session);
            }
            return;
        }

        processors = new ArrayList<Processor>();
        computeProcessors(this, processors);


        try {
            setupMonitor(project);
        } catch (FileSystemException e) {
            throw new MojoExecutionException("Cannot set the file monitor on the source folder", e);
        }

        // Starts all others process and monitors on the others project
        for (WatchMojo watcher : ReactorUtils.getWatchersFromSession(session)) {
            computeProcessors(watcher, processors);
            DefaultProcessor proc = new FinalArtifactProcessor(this, watcher);
            proc.configure(this, null);
            processors.add(proc);
            try {
                setupMonitor(watcher.project);
            } catch (FileSystemException e) {
                throw new MojoExecutionException("Cannot set the file monitor on the source folder", e);
            }
        }

        String MESSAGE = "You're running the watch mode. All modified files will be processed " +
                "automatically. \n" +
                "If the jetty server is enabled, they will also be served from http://localhost:" +
                watchJettyServerPort + "/. \n" +
                "The jasmine runner is available from http://localhost:" + watchJettyServerPort + "/jasmine. \n" +
                "To leave the watch mode, just hit CTRL+C.\n";
        getLog().info(MESSAGE);

        for (Processor processor : processors) {
            try {
                processor.processAll();
            } catch (Processor.ProcessorException e) {
                getLog().error("", e);
            }
        }

        if (watchRunServer) {
            try {
                server = new Server();
                addConnectorToServer();
                addHandlersToServer();
                startServer();
            } catch (Exception e){
                throw new MojoExecutionException("Cannot run the jetty server", e);
            }
        } else {
            try {
                Thread.sleep(1000000000); // Pretty long
            } catch (InterruptedException e) { /* ignore */ }
        }
    }

    private List<Processor> computeProcessors(WatchMojo mojo, List<Processor> processors) {
        // Always added

        // Asset Copy
        DefaultProcessor processor = new CopyAssetProcessor();
        processor.configure(mojo, null);
        processors.add(processor);

        // Copy JS Main + Test
        processor = new JavaScriptFileCopyProcessor();
        processor.configure(mojo, new OptionsHelper.OptionsBuilder().set("test", false).build());
        processors.add(processor);
        processor = new JavaScriptFileCopyProcessor();
        processor.configure(mojo, new OptionsHelper.OptionsBuilder().set("test",
                true).build());
        processors.add(processor);

        // Copy CSS
        processor = new CSSFileCopyProcessor();
        processor.configure(mojo, null);
        processors.add(processor);

        // Less
        if (watchLess) {
            processor = new LessCompilationProcessor();
            processor.configure(mojo, null);
            processors.add(processor);
        }

        // CoffeeScript
        if (watchCoffeeScript) {
            processor = new CoffeeScriptCompilationProcessor();
            processor.configure(mojo, new OptionsHelper.OptionsBuilder().set("test",
                    false).build());
            processors.add(processor);

            processor = new CoffeeScriptCompilationProcessor();
            processor.configure(mojo, new OptionsHelper.OptionsBuilder().set("test",
                    true).build());
            processors.add(processor);
        }

        if (watchDust) {
            processor = new DustJSProcessor();
            processor.configure(mojo, null);
            processors.add(processor);
        }

        // JS and CSS Aggregation
        if (watchDoAggregate) {
            processor = new JavaScriptAggregator();
            Map<String, Object> options = new HashMap<String, Object>();
            File output = new File(mojo.getWorkDirectory(), mojo.project.getBuild().getFinalName() + ".js");
            options.put("output", output);
            options.put("names", mojo.javascriptAggregation);
            options.put("extension", "js");
            processor.configure(mojo, options);
            processors.add(processor);

            processor = new CSSAggregator();
            output = new File(mojo.getWorkDirectory(), mojo.project.getBuild().getFinalName() + ".css");
            options = new HashMap<String, Object>();
            options.put("output", output);
            options.put("names", mojo.cssAggregation);
            options.put("extension", "css");
            processor.configure(mojo, options);
            processors.add(processor);
        }

        // CSSLint, JSLint and JSHint validation
        if (watchValidateJS) {
            processor = new JSHintProcessor();
            processor.configure(mojo, null);
            processors.add(processor);

            processor = new JSHintProcessor();
            processor.configure(mojo, null);
            processors.add(processor);
        }
        if (watchValidateCSS) {
            processor = new CSSLintProcessor();
            processor.configure(mojo, new OptionsHelper.OptionsBuilder().set("directory",
                    mojo.getWorkDirectory()).build());
            processors.add(processor);
        }

        if (watchOptimizeAssets) {
            // Asset optimization
            processor = new OptiPNGProcessor();
            processor.configure(mojo, new OptionsHelper.OptionsBuilder().set("verbose", true).set("level",
                    optiPngOptimizationLevel).build());

            processor = new JpegTranProcessor();
            processor.configure(this, new OptionsHelper.OptionsBuilder().set("verbose", true).build());

            // HTML Compression
            processor = new HTMLCompressorProcessor();
            Map<String, Object> options = new OptionsHelper.OptionsBuilder()
                    .set("preserveLineBreak", true)
                    .set("removeComments", true)
                    .set("removeMultispaces", true)
                    .set("removeFormAttributes", true)
                    .set("removeHttpProtocol", true)
                    .set("removeHttpsProtocol", true)
                    .set("removeInputAttributes", true)
                    .set("removeIntertagSpaces", true)
                    .set("removeJavascriptProtocol", true)
                    .set("removeLinkAttributes", true)
                    .set("removeQuotes", true)
                    .set("removeScriptAttributes", true)
                    .set("simpleBooleanAttributes", true)
                    .set("removeStyleAttributes", true)
                    .set("simpleDocType", true)
                    .build();
            processor.configure(this, options);
        }

        return processors;
    }


    private void setupMonitor(MavenProject project) throws FileSystemException {
        File baseDir = project.getBasedir();
        getLog().info("Set up file monitor on " + baseDir);
        FileSystemManager fsManager = VFS.getManager();
        FileObject dir = fsManager.resolveFile(baseDir.getAbsolutePath());

        DefaultFileMonitor fm = new DefaultFileMonitor(this);
        fm.setRecursive(true);
        fm.addFile(dir);
        fm.start();
    }

    private void addConnectorToServer() {
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(watchJettyServerPort);
        server.addConnector(connector);
    }

    private void addHandlersToServer() {
        HandlerList list = new HandlerList();
        list.addHandler(new DirectoryHandler(getWorkDirectory()));
        list.addHandler(new DirectoryHandler(getLibDirectory()));
        list.addHandler(new DirectoryHandler(getWorkTestDirectory()));
        list.addHandler(new JasmineHandler(this));
        server.setHandler(list);
    }

    private void startServer() throws Exception {
        server.start();
        server.join();
    }

    public void fileCreated(FileChangeEvent event) throws Exception {
        getLog().info("New file found " + event.getFile().getName().getBaseName());
        boolean processed = false;
        String path = event.getFile().getName().getPath();
        File theFile = new File(path);
        for (Processor processor : processors) {
            if (processor.accept(theFile)) {
                processed = true;
                processor.fileCreated(theFile);
            }
        }

        if (! processed) {
            getLog().info("Nothing to do for " + event.getFile().getName().getBaseName());
        }
    }

    public void fileDeleted(FileChangeEvent event) throws Exception {
        getLog().info("File " + event.getFile().getName().getBaseName() + " deleted");
        boolean processed = false;
        String path = event.getFile().getName().getPath();
        File theFile = new File(path);
        for (Processor processor : processors) {
            if (processor.accept(theFile)) {
                processed = true;
                processor.fileDeleted(theFile);
            }
        }

        if (! processed) {
            getLog().info("Nothing to do for " + event.getFile().getName().getBaseName());
        }
    }

    public void fileChanged(FileChangeEvent event) throws Exception {
        getLog().info("File changed: " + event.getFile().getName().getBaseName());
        boolean processed = false;
        String path = event.getFile().getName().getPath();
        File theFile = new File(path);
        for (Processor processor : processors) {
            if (processor.accept(theFile)) {
                processed = true;
                processor.fileUpdated(theFile);
            }
        }

        if (! processed) {
            getLog().info("Nothing to do for " + event.getFile().getName().getBaseName());
        }
    }
}
