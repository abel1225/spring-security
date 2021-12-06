/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package s101;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.JavaExec;

public class S101Plugin implements Plugin<Project> {
	@Override
	public void apply(Project project) {
		project.getExtensions().add("s101", new S101PluginExtension(project));
		project.getTasks().register("s101Install", S101Install.class, this::configure);
		project.getTasks().register("s101Configure", S101Configure.class, this::configure);
		project.getTasks().register("s101", JavaExec.class, this::configure);
	}

	private void configure(S101Install install) {
		install.setDescription("Installs Structure101 to your filesystem");
	}

	private void configure(S101Configure configure) {
		configure.setDescription("Applies a default Structure101 configuration to the project");
	}

	private void configure(JavaExec exec) {
		exec.setDescription("Runs Structure101 headless analysis, installing and configuring if necessary");
		Project project = exec.getProject();
		S101PluginExtension extension = project.getExtensions().getByType(S101PluginExtension.class);
		addInputsAndOutputs(project, exec);
		exec
				.workingDir(extension.getInstallationDirectory())
				.classpath(new File(extension.getInstallationDirectory().get(), "structure101-java-build.jar"))
				.args(new File(new File(project.getBuildDir(), "s101"), "config.xml"))
				.systemProperty("s101.label", computeLabel(extension).get())
				.doFirst(new Action<Task>() {
					@Override
					public void execute(Task task) {
						installAndConfigureIfNeeded(project);
						copyConfigurationToBuildDirectory(extension, project);
					}
				})
				.doLast(new Action<Task>() {
					@Override
					public void execute(Task task) {
						copyResultsBackToConfigurationDirectory(extension, project);
					}
				});
	}

	private void addInputsAndOutputs(Project project, JavaExec exec) {
		Pattern buildPathLine = Pattern.compile("<classpathentry kind=\"lib\" path=\"(.*)\" module=\"(.*)\" />");
		File hsp = new File(new File(project.getBuildDir(), "s101"), "project.java.hsp");
		if (!hsp.exists()) {
			return;
		}
		Set<Task> task = project.getTasksByName("compileJava", true);
		Map<String, Task> checkByModuleName = task.stream()
				.collect(Collectors.toMap((t) -> t.getProject().getName(), Function.identity()));
		List<String> configLines = readAllLines(hsp);
		for (String configLine : configLines) {
			Matcher matcher = buildPathLine.matcher(configLine);
			if (matcher.find()) {
				String module = matcher.group(2);
				Task moduleTask = checkByModuleName.remove(module);
				if (moduleTask != null) {
					exec.getInputs().files(moduleTask.getOutputs().getFiles());
				}
			}
		}
	}

	private List<String> readAllLines(File file) {
		try {
			return Files.readAllLines(file.toPath());
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private Property<String> computeLabel(S101PluginExtension extension) {
		boolean hasBaseline = extension.getConfigurationDirectory().get().toPath()
				.resolve("repository").resolve("snapshots").resolve("baseline").toFile().exists();
		if (!hasBaseline) {
			return extension.getLabel().convention("baseline");
		}
		return extension.getLabel().convention("recent");
	}

	private void installAndConfigureIfNeeded(Project project) {
		S101Configurer configurer = new S101Configurer(project);
		S101PluginExtension extension = project.getExtensions().getByType(S101PluginExtension.class);
		String licenseId = extension.getLicenseId().getOrNull();
		if (licenseId != null) {
			configurer.license(licenseId);
		}
		File installationDirectory = extension.getInstallationDirectory().get();
		File configurationDirectory = extension.getConfigurationDirectory().get();
		if (!installationDirectory.exists()) {
			configurer.install(installationDirectory, configurationDirectory);
		}
		if (!configurationDirectory.exists()) {
			configurer.configure(installationDirectory, configurationDirectory);
		}
	}

	private void copyConfigurationToBuildDirectory(S101PluginExtension extension, Project project) {
		Path configurationDirectory = extension.getConfigurationDirectory().get().toPath();
		Path buildDirectory = project.getBuildDir().toPath();
		copyDirectory(project, configurationDirectory, buildDirectory);
	}

	private void copyResultsBackToConfigurationDirectory(S101PluginExtension extension, Project project) {
		Path buildConfigurationDirectory = project.getBuildDir().toPath().resolve("s101");
		String label = extension.getLabel().get();
		if ("baseline".equals(label)) { // a new baseline was created
			copyDirectory(project, buildConfigurationDirectory.resolve("repository").resolve("snapshots"),
					extension.getConfigurationDirectory().get().toPath().resolve("repository"));
			copyDirectory(project, buildConfigurationDirectory.resolve("repository"),
					extension.getConfigurationDirectory().get().toPath());
		}
	}

	private void copyDirectory(Project project, Path source, Path destination) {
		try {
			Files.walk(source)
					.forEach(each -> {
						Path relativeToSource = source.getParent().relativize(each);
						Path resolvedDestination = destination.resolve(relativeToSource);
						if (each.toFile().isDirectory()) {
							resolvedDestination.toFile().mkdirs();
							return;
						}
						InputStream input;
						if ("project.java.hsp".equals(each.toFile().getName())) {
							Path relativeTo = project.getBuildDir().toPath().resolve("s101").relativize(project.getProjectDir().toPath());
							String value = "const(THIS_FILE)/" + relativeTo;
							input = replace(each, "<property name=\"relative-to\" value=\"(.*)\" />", "<property name=\"relative-to\" value=\"" + value + "\" />");
						} else if (each.toFile().toString().endsWith(".xml")) {
							input = replace(each, "\\r\\n", "\n");
						} else {
							input = input(each);
						}
						try {
							Files.copy(input, resolvedDestination, StandardCopyOption.REPLACE_EXISTING);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					});
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private InputStream replace(Path file, String search, String replace) {
		try {
			byte[] b = Files.readAllBytes(file);
			String contents = new String(b).replaceAll(search, replace);
			return new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private InputStream input(Path file) {
		try {
			return new FileInputStream(file.toFile());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
