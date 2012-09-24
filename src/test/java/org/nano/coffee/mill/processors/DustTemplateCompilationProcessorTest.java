package org.nano.coffee.mill.processors;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.Test;
import org.nano.coffee.mill.mojos.compile.CoffeeScriptCompilerMojo;
import org.nano.coffee.mill.mojos.compile.CoffeeScriptTestCompilerMojo;
import org.nano.coffee.mill.mojos.compile.JavaScriptCompilerMojo;

import java.io.File;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;

public class DustTemplateCompilationProcessorTest {

    @Test
    public void testDustCompilation() throws MojoExecutionException, MojoFailureException {
        JavaScriptCompilerMojo mojo = new JavaScriptCompilerMojo();
        mojo.javaScriptDir = new File("src/test/resources/js");
        mojo.workDir = new File("target/test/testDustCompilation-www");
        mojo.execute();

        assertThat(new File(mojo.workDir, "sample/templates/mytemplate.js").isFile()).isTrue();
    }

    @Test
    public void testWhenJavaScriptDirectoryDoesNotExist() throws MojoExecutionException,
            MojoFailureException {
        JavaScriptCompilerMojo mojo = new JavaScriptCompilerMojo();
        mojo.javaScriptDir = new File("src/test/resources/does_not_exist");
        mojo.workDir = new File("target/test/testDustCompilation-www");
        mojo.execute();
    }
}