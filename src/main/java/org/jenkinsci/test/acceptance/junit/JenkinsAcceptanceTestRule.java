package org.jenkinsci.test.acceptance.junit;

import com.google.inject.Inject;
import com.google.inject.Injector;

import org.jenkinsci.test.acceptance.controller.JenkinsController;
import org.jenkinsci.test.acceptance.guice.World;
import org.junit.internal.AssumptionViolatedException;
import org.junit.rules.MethodRule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.annotation_indexer.Index;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs Guice container that houses {@link JenkinsController}, {@link WebDriver}, and so on.
 *
 * <p>
 * Add this rule to your Unit test class if you want to leverage this harness.
 *
 * <p>
 * This is the glue that connects JUnit to the logic of the test harness.
 *
 * @author Kohsuke Kawaguchi
 */
public class JenkinsAcceptanceTestRule implements MethodRule { // TODO should use TestRule instead
    @Override
    public Statement apply(final Statement base, final FrameworkMethod method, final Object target) {
        final Description description = Description.createTestDescription(target.getClass(), method.getName(), method.getAnnotations());
        return new Statement() {
            @Inject JenkinsController controller;
            @Inject Injector injector;
            @Inject FailureDiagnostics diagnostics;
            @Inject WebDriver driver;

            @Override
            public void evaluate() throws Throwable {
                World world = World.get();
                Injector injector = world.getInjector();

                world.startTestScope(description.getDisplayName());

                injector.injectMembers(this);

                System.out.println("=== Starting " + description.getDisplayName());
                try {
                    decorateWithRules(base).evaluate();
                } catch (AssumptionViolatedException e) {
                    System.out.printf("Skipping %s%n", description.getDisplayName());
                    e.printStackTrace();
                    throw e;
                } catch (Exception|AssertionError e) { // Errors and failures
                    if (causedBy(e, NoSuchElementException.class) != null) {
                        diagnostics.write("last-page.html", driver.getPageSource());
                    }
                    controller.diagnose(e);
                    throw e;
                } finally {
                    world.endTestScope();
                }
            }

            /**
             * Detect the outermost exception of given type.
             */
            private Throwable causedBy(Throwable caught, Class<? extends Throwable> type) {
                for (Throwable cur = caught; cur != null; cur = cur.getCause()) {
                    if (type.isAssignableFrom(caught.getClass())) return cur;
                }
                return null;
            }

            /**
             * Look for annotations on a test and honor {@link RuleAnnotation}s in them.
             */
            private Statement decorateWithRules(Statement body) {

                TreeMap<Integer, Set<TestRule>> rules = new TreeMap<Integer, Set<TestRule>>(new Comparator<Integer>() {
                    @Override
                    public int compare(Integer o1, Integer o2) {
                        // Reversed since we apply the TestRule inside out:
                        return Integer.compare(o2, o1);
                    }
                });

                collectRuleAnnotations(method, target, rules);
                collectGlobalRules(rules);

                // Make sure Jenkins is started between -1 and 0
                if (rules.get(0) == null) {
                    rules.put(0, new LinkedHashSet<TestRule>());
                }
                rules.get(0).add(jenkinsBoot(rules));

                for (Set<TestRule> rulesGroup: rules.values()) {
                    for (TestRule rule: rulesGroup) {
                        body = rule.apply(body, description);
                    }
                }
                return body;
            }

            private void collectGlobalRules(TreeMap<Integer, Set<TestRule>> rules) {
                Iterable<Class> impls;
                try {
                    impls = Index.list(GlobalRule.class, getClass().getClassLoader(), Class.class);
                } catch (IOException e) {
                    throw new Error("Unable to collect global annotations", e);
                }

                for (Class<?> rule: impls) {
                    if (!TestRule.class.isAssignableFrom(rule)) {
                        throw new Error("GlobalRule is applicable for TestRules only");
                    }

                    addRule(rules, rule.getAnnotation(GlobalRule.class).priority(), (Class<? extends TestRule>) rule);
                }
            }

            private void collectRuleAnnotations(final FrameworkMethod method, final Object target, TreeMap<Integer, Set<TestRule>> rules) {
                Set<Class<? extends Annotation>> annotations = new HashSet<>();
                collectAnnotationTypes(method.getMethod(), annotations);
                collectAnnotationTypes(target.getClass(), annotations);
                for (Class<? extends  Annotation> a : annotations) {
                    RuleAnnotation r = a.getAnnotation(RuleAnnotation.class);
                    if (r!=null) {
                        addRule(rules, r.priority(), r.value());
                    }
                }
            }

            private void collectAnnotationTypes(AnnotatedElement e, Collection<Class<? extends Annotation>> types) {
                for (Annotation a : e.getAnnotations()) {
                    types.add(a.annotationType());
                }
            }

            private void addRule(TreeMap<Integer, Set<TestRule>> rules, int prio, Class<? extends TestRule> impl) {
                if (rules.get(prio) == null) {
                    rules.put(prio, new LinkedHashSet<TestRule>());
                }
                rules.get(prio).add(injector.getInstance(impl));
            }

            private TestRule jenkinsBoot(final TreeMap<Integer, Set<TestRule>> rules) {
                return new TestRule() {
                    @Override
                    public Statement apply(final Statement base, Description description) {
                        return new Statement() {
                            @Override public void evaluate() throws Throwable {
                                controller.start();
                                // Now it is safe to inject Jenkins
                                injector.injectMembers(target);
                                for (Set<TestRule> rg: rules.values()) {
                                    for (TestRule rule: rg) {
                                        injector.injectMembers(rule);
                                    }
                                }
                                base.evaluate();
                            }
                        };
                    }
                };
            }
        };
    }
}
