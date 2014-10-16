package javaposse.jobdsl.dsl

import com.google.common.base.Preconditions
import javaposse.jobdsl.dsl.helpers.ContextHelper
import javaposse.jobdsl.dsl.helpers.AuthorizationContext
import javaposse.jobdsl.dsl.helpers.AxisContext
import javaposse.jobdsl.dsl.helpers.BuildParametersContext
import javaposse.jobdsl.dsl.helpers.Permissions
import javaposse.jobdsl.dsl.helpers.ScmContext
import javaposse.jobdsl.dsl.helpers.common.MavenContext
import javaposse.jobdsl.dsl.helpers.publisher.PublisherContext
import javaposse.jobdsl.dsl.helpers.step.StepContext
import javaposse.jobdsl.dsl.helpers.toplevel.EnvironmentVariableContext
import javaposse.jobdsl.dsl.helpers.toplevel.LockableResourcesContext
import javaposse.jobdsl.dsl.helpers.toplevel.NotificationContext
import javaposse.jobdsl.dsl.helpers.toplevel.ThrottleConcurrentBuildsContext
import javaposse.jobdsl.dsl.helpers.triggers.TriggerContext
import javaposse.jobdsl.dsl.helpers.wrapper.WrapperContext

import static groovy.lang.Closure.DELEGATE_FIRST as DF

/**
 * DSL element representing a Jenkins job.
 */
class Job extends Item {
    private final List<String> mavenGoals = []
    private final List<String> mavenOpts = []

    JobManagement jobManagement

    String templateName = null // Optional
    JobType type = null // Required

    Job(JobManagement jobManagement, Map<String, Object> arguments=[:]) {
        this.jobManagement = jobManagement
        def typeArg = arguments['type'] ?: JobType.Freeform
        this.type = (typeArg instanceof JobType) ? typeArg : JobType.find(typeArg)
    }

    /**
     * Creates a new job configuration, based on the job template referenced by the parameter and stores this.
     * @param templateName the name of the template upon which to base the new job
     * @return a new graph of groovy.util.Node objects, representing the job configuration structure
     * @throws JobTemplateMissingException
     */
    def using(String templateName) throws JobTemplateMissingException {
        Preconditions.checkState(this.templateName == null, 'Can only use "using" once')
        this.templateName = templateName
    }

    @Deprecated
    def name(Closure nameClosure) {
        jobManagement.logDeprecationWarning()
        name(nameClosure.call().toString())
    }

    def description(String descriptionString) {
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = methodMissing('description', descriptionString)
            project / node
        }
    }

    /**
     * "Restrict where this project can be run"
     * <assignedNode>FullTools&amp;&amp;RPM&amp;&amp;DC</assignedNode>
     * @param labelExpression Label of node to use, if null is passed in, the label is cleared out and it can roam
     * @return
     */
    def label(String labelExpression = null) {
        withXmlActions << WithXmlAction.create { Node project ->
            if (labelExpression) {
                project / assignedNode(labelExpression)
                project / canRoam(false) // If canRoam is true, the label will not be used
            } else {
                project / assignedNode('')
                project / canRoam(true)
            }
        }
    }

    /**
     * Add environment variables to the build.
     *
     * <project>
     *   <properties>
     *     <EnvInjectJobProperty>
     *       <info>
     *         <propertiesContent>TEST=foo BAR=123</propertiesContent>
     *         <loadFilesFromMaster>false</loadFilesFromMaster>
     *       </info>
     *       <on>true</on>
     *       <keepJenkinsSystemVariables>true</keepJenkinsSystemVariables>
     *       <keepBuildVariables>true</keepBuildVariables>
     *       <contributors/>
     *     </EnvInjectJobProperty>
     */
    def environmentVariables(@DelegatesTo(value = EnvironmentVariableContext, strategy = DF) Closure closure) {
        environmentVariables(null, closure)
    }

    def environmentVariables(Map<Object, Object> vars,
                             @DelegatesTo(value = EnvironmentVariableContext, strategy = DF) Closure closure = null) {
        EnvironmentVariableContext envContext = new EnvironmentVariableContext()
        if (vars) {
            envContext.envs(vars)
        }
        ContextHelper.executeInContext(closure, envContext)

        withXmlActions << WithXmlAction.create { Node project ->
            project / 'properties' / 'EnvInjectJobProperty' {
                envContext.addInfoToBuilder(delegate)
                on(true)
                keepJenkinsSystemVariables(envContext.keepSystemVariables)
                keepBuildVariables(envContext.keepBuildVariables)
                contributors()
            }
        }
    }

    /**
     * <project>
     *     <properties>
     *         <hudson.plugins.throttleconcurrents.ThrottleJobProperty>
     *             <maxConcurrentPerNode>0</maxConcurrentPerNode>
     *             <maxConcurrentTotal>0</maxConcurrentTotal>
     *             <categories>
     *                 <string>CDH5-repo-update</string>
     *             </categories>
     *             <throttleEnabled>true</throttleEnabled>
     *             <throttleOption>category</throttleOption>
     *         </hudson.plugins.throttleconcurrents.ThrottleJobProperty>
     *     <properties>
     * </project>
     */
    def throttleConcurrentBuilds(
            @DelegatesTo(value = ThrottleConcurrentBuildsContext, strategy = DF) Closure throttleClosure) {
        ThrottleConcurrentBuildsContext throttleContext = new ThrottleConcurrentBuildsContext()
        ContextHelper.executeInContext(throttleClosure, throttleContext)

        withXmlActions << WithXmlAction.create { Node project ->
            project / 'properties' / 'hudson.plugins.throttleconcurrents.ThrottleJobProperty' {
                maxConcurrentPerNode throttleContext.maxConcurrentPerNode
                maxConcurrentTotal throttleContext.maxConcurrentTotal
                throttleEnabled throttleContext.throttleDisabled ? 'false' : 'true'
                if (throttleContext.categories.isEmpty()) {
                    throttleOption 'project'
                } else {
                    throttleOption 'category'
                }
                categories {
                    throttleContext.categories.each { c ->
                        string c
                    }
                }
            }
        }
    }

    /**
     * <project>
     *     <properties>
     *         <org.jenkins.plugins.lockableresources.RequiredResourcesProperty>
     *             <resourceNames>lock-resource</resourceNames>
     *             <resourceNamesVar>NAMES</resourceNamesVar>
     *             <resourceNumber>0</resourceNumber>
     *         </org.jenkins.plugins.lockableresources.RequiredResourcesProperty>
     *     <properties>
     * </project>
     */
    def lockableResources(String resources,
                          @DelegatesTo(value = LockableResourcesContext, strategy = DF) Closure lockClosure = null) {
        LockableResourcesContext lockContext = new LockableResourcesContext()
        ContextHelper.executeInContext(lockClosure, lockContext)

        withXmlActions << WithXmlAction.create { Node project ->
            project / 'properties' / 'org.jenkins.plugins.lockableresources.RequiredResourcesProperty' {
                resourceNames resources
                if (lockContext.resourcesVariable) {
                    resourceNamesVar lockContext.resourcesVariable
                }
                if (lockContext.resourceNumber != null) {
                    resourceNumber lockContext.resourceNumber
                }
            }
        }
    }

    /**
     * <disabled>true</disabled>
     */
    def disabled(boolean shouldDisable = true) {
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = methodMissing('disabled', shouldDisable)
            project / node
        }
    }

    /**
     * <logRotator>
     *     <daysToKeep>14</daysToKeep>
     *     <numToKeep>50</numToKeep>
     *     <artifactDaysToKeep>5</artifactDaysToKeep>
     *     <artifactNumToKeep>20</artifactNumToKeep>
     * </logRotator>
     */
    def logRotator(int daysToKeepInt = -1, int numToKeepInt = -1,
                   int artifactDaysToKeepInt = -1, int artifactNumToKeepInt = -1) {
        withXmlActions << WithXmlAction.create { Node project ->
            project / logRotator {
                daysToKeep daysToKeepInt
                numToKeep numToKeepInt
                artifactDaysToKeep artifactDaysToKeepInt
                artifactNumToKeep artifactNumToKeepInt
            }
        }
    }

    /**
     * Block build if certain jobs are running
     * <properties>
     *     <hudson.plugins.buildblocker.BuildBlockerProperty>
     *         <useBuildBlocker>true</useBuildBlocker>  <!-- Always true -->
     *         <blockingJobs>JobA</blockingJobs>
     *     </hudson.plugins.buildblocker.BuildBlockerProperty>
     * </properties>
     */
    def blockOn(Iterable<String> projectNames) {
        blockOn(projectNames.join('\n'))
    }

    /**
     * Block build if certain jobs are running.
     * @param projectName Can be regular expressions. Newline delimited.
     * @return
     */
    def blockOn(String projectName) {
        withXmlActions << WithXmlAction.create { Node project ->
            project / 'properties' / 'hudson.plugins.buildblocker.BuildBlockerProperty' {
                useBuildBlocker 'true'
                blockingJobs projectName
            }
        }
    }

    /**
     * Name of the JDK installation to use for this job.
     * @param jdkArg name of the JDK installation to use for this job.
     */
    def jdk(String jdkArg) {
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = methodMissing('jdk', jdkArg)
            project / node
        }
    }

    /**
     * Priority of this job. Requires the
     * <a href="https://wiki.jenkins-ci.org/display/JENKINS/Priority+Sorter+Plugin">Priority Sorter Plugin</a>.
     * Default value is 100.
     *
     * <properties>
     *     <hudson.queueSorter.PrioritySorterJobProperty plugin="PrioritySorter@1.3">
     *         <priority>100</priority>
     *     </hudson.queueSorter.PrioritySorterJobProperty>
     * </properties>
     */
    def priority(int value) {
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = new Node(project / 'properties', 'hudson.queueSorter.PrioritySorterJobProperty')
            node.appendNode('priority', value)
        }
    }

    /**
     * Adds a quiet period to the project.
     *
     * @param seconds number of seconds to wait
     */
    def quietPeriod(int seconds = 5) {
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = methodMissing('quietPeriod', seconds)
            project / node
        }
    }

    /**
     * Sets the number of times the SCM checkout is retried on errors.
     *
     * @param times number of attempts
     */
    def checkoutRetryCount(int times = 3) {
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = methodMissing('scmCheckoutRetryCount', times)
            project / node
        }
    }

    /**
     * Sets a display name for the project.
     *
     * @param displayName name to display
     */
    def displayName(String displayName) {
        Preconditions.checkNotNull(displayName, 'Display name must not be null.')
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = methodMissing('displayName', displayName)
            project / node
        }
    }

    /**
     * Configures a custom workspace for the project.
     *
     * @param workspacePath workspace path to use
     */
    def customWorkspace(String workspacePath) {
        Preconditions.checkNotNull(workspacePath, 'Workspace path must not be null')
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = methodMissing('customWorkspace', workspacePath)
            project / node
        }
    }

    /**
     * Configures the job to block when upstream projects are building.
     */
    def blockOnUpstreamProjects() {
        withXmlActions << WithXmlAction.create { Node project ->
            project / blockBuildWhenUpstreamBuilding(true)
        }
    }

    /**
     * Configures the job to block when downstream projects are building.
     */
    def blockOnDownstreamProjects() {
        withXmlActions << WithXmlAction.create { Node project ->
            project / blockBuildWhenDownstreamBuilding(true)
        }
    }

    /**
     * Configures the keep Dependencies Flag which can be set in the Fingerprinting action
     *
     * <keepDependencies>true</keepDependencies>
     */
    def keepDependencies(boolean keep = true) {
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = methodMissing('keepDependencies', keep)
            project / node
        }
    }

    /**
     * Configures the 'Execute concurrent builds if necessary' flag
     *
     * <concurrentBuild>true</concurrentBuild>
     */
    def concurrentBuild(boolean allowConcurrentBuild = true) {
        withXmlActions << WithXmlAction.create { Node project ->
            Node node = methodMissing('concurrentBuild', allowConcurrentBuild)
            project / node
        }
    }

    /**
     * Configures the Notification Plugin.
     *
     * <properties>
     *     <com.tikal.hudson.plugins.notification.HudsonNotificationProperty>
     *         <endpoints>
     *             <com.tikal.hudson.plugins.notification.Endpoint>
     *                 <protocol>HTTP</protocol>
     *                 <format>JSON</format>
     *                 <url />
     *                 <event>all</event>
     *                 <timeout>30000</timeout>
     *             </com.tikal.hudson.plugins.notification.Endpoint>
     *         </endpoints>
     *     </com.tikal.hudson.plugins.notification.HudsonNotificationProperty>
     * </properties>
     */
    def notifications(@DelegatesTo(value = NotificationContext, strategy = DF) Closure closure) {
        NotificationContext notificationContext = new NotificationContext(jobManagement)
        ContextHelper.executeInContext(closure, notificationContext)

        withXmlActions << WithXmlAction.create { Node project ->
            project / 'properties' / 'com.tikal.hudson.plugins.notification.HudsonNotificationProperty' {
                endpoints notificationContext.endpoints
            }
        }
    }

    /**
     * <properties>
     *     <hudson.plugins.batch__task.BatchTaskProperty>
     *         <tasks>
     *             <hudson.plugins.batch__task.BatchTask>
     *                 <name>Hello World</name>
     *                 <script>echo Hello World</script>
     *             </hudson.plugins.batch__task.BatchTask>
     *         </tasks>
     *     </hudson.plugins.batch__task.BatchTaskProperty>
     * </properties>
     */
    def batchTask(String name, String script) {
        withXmlActions << WithXmlAction.create { Node project ->
            def batchTaskProperty = project / 'properties' / 'hudson.plugins.batch__task.BatchTaskProperty'
            batchTaskProperty / 'tasks' << 'hudson.plugins.batch__task.BatchTask' {
                delegate.name name
                delegate.script script
            }
        }
    }

    /**
     * <properties>
     *     <se.diabol.jenkins.pipeline.PipelineProperty>
     *         <taskName>integration-tests</taskName>
     *         <stageName>qa</stageName>
     *     </se.diabol.jenkins.pipeline.PipelineProperty>
     * </properties>
     */
    def deliveryPipelineConfiguration(String stageName, String taskName = null) {
        if (stageName || taskName) {
            withXmlActions << WithXmlAction.create { Node project ->
                project / 'properties' / 'se.diabol.jenkins.pipeline.PipelineProperty' {
                    if (taskName) {
                        delegate.taskName(taskName)
                    }
                    if (stageName) {
                        delegate.stageName(stageName)
                    }
                }
            }
        }
    }

    def authorization(@DelegatesTo(value = AuthorizationContext, strategy = DF) Closure closure) {
        AuthorizationContext context = new AuthorizationContext()
        ContextHelper.executeInContext(closure, context)

        withXmlActions << WithXmlAction.create { Node project ->
            def authorizationMatrixProperty = project / 'properties' / 'hudson.security.AuthorizationMatrixProperty'
            context.permissions.each { String perm ->
                authorizationMatrixProperty.appendNode('permission', perm)
            }
        }
    }

    @Deprecated
    def permission(String permission) {
        jobManagement.logDeprecationWarning()

        authorization {
            delegate.permission(permission)
        }
    }

    @Deprecated
    def permission(Permissions permission, String user) {
        jobManagement.logDeprecationWarning()

        authorization {
            delegate.permission(permission, user)
        }
    }

    @Deprecated
    def permission(String permissionEnumName, String user) {
        jobManagement.logDeprecationWarning()

        authorization {
            delegate.permission(permissionEnumName, user)
        }
    }

    def parameters(@DelegatesTo(value = BuildParametersContext, strategy = DF) Closure closure) {
        BuildParametersContext context = new BuildParametersContext()
        ContextHelper.executeInContext(closure, context)

        withXmlActions << WithXmlAction.create { Node project ->
            def node = project / 'properties' / 'hudson.model.ParametersDefinitionProperty' / 'parameterDefinitions'
            context.buildParameterNodes.values().each {
                node << it
            }
        }
    }

    def scm(@DelegatesTo(value = ScmContext, strategy = DF) Closure closure) {
        ScmContext context = new ScmContext(false, withXmlActions, jobManagement)
        ContextHelper.executeInContext(closure, context)

        withXmlActions << WithXmlAction.create { Node project ->
            def scm = project / scm
            if (scm) {
                // There can only be only one SCM, so remove if there
                project.remove(scm)
            }

            // Assuming append the only child
            project << context.scmNode
        }
    }

    def multiscm(@DelegatesTo(value = ScmContext, strategy = DF) Closure closure) {
        ScmContext context = new ScmContext(true, withXmlActions, jobManagement)
        ContextHelper.executeInContext(closure, context)

        withXmlActions << WithXmlAction.create { Node project ->
            def scm = project / scm
            if (scm) {
                // There can only be only one SCM, so remove if there
                project.remove(scm)
            }

            def multiscmNode = new NodeBuilder().scm(class: 'org.jenkinsci.plugins.multiplescms.MultiSCM')
            def scmsNode = multiscmNode / scms
            context.scmNodes.each {
                scmsNode << it
            }

            // Assuming append the only child
            project << multiscmNode
        }
    }

    def triggers(@DelegatesTo(value = TriggerContext, strategy = DF) Closure closure) {
        TriggerContext context = new TriggerContext(withXmlActions, type, jobManagement)
        ContextHelper.executeInContext(closure, context)

        withXmlActions << WithXmlAction.create { Node project ->
            context.triggerNodes.each {
                project / 'triggers' << it
            }
        }
    }

    def wrappers(@DelegatesTo(value = WrapperContext, strategy = DF) Closure closure) {
        WrapperContext context = new WrapperContext(type, jobManagement)
        ContextHelper.executeInContext(closure, context)

        withXmlActions << WithXmlAction.create { Node project ->
            context.wrapperNodes.each {
                project / 'buildWrappers' << it
            }
        }
    }

    def steps(@DelegatesTo(value = StepContext, strategy = DF) Closure closure) {
        Preconditions.checkState(type != JobType.Maven, 'steps cannot be applied for Maven jobs')

        StepContext context = new StepContext(jobManagement)
        ContextHelper.executeInContext(closure, context)

        withXmlActions << WithXmlAction.create { Node project ->
            context.stepNodes.each {
                project / 'builders' << it
            }
        }
    }

    def publishers(@DelegatesTo(value = PublisherContext, strategy = DF) Closure closure) {
        PublisherContext context = new PublisherContext(jobManagement)
        ContextHelper.executeInContext(closure, context)

        withXmlActions << WithXmlAction.create { Node project ->
            context.publisherNodes.each {
                project / 'publishers' << it
            }
        }
    }

    def buildFlow(String buildFlowText) {
        Preconditions.checkState(type == JobType.BuildFlow, 'Build Flow text can only be applied to Build Flow jobs.')

        withXmlActions << WithXmlAction.create { Node project ->
            project / dsl(buildFlowText)
        }
    }

    def axes(@DelegatesTo(value = AxisContext, strategy = DF) Closure closure) {
        Preconditions.checkState(type == JobType.Matrix, 'axes can only be applied for Matrix jobs')

        AxisContext context = new AxisContext()
        ContextHelper.executeInContext(closure, context)

        withXmlActions << WithXmlAction.create { Node project ->
            Node axesNode = project / 'axes'
            context.axisNodes.each {
                axesNode  << it
            }
            context.configureBlocks.each {
                new WithXmlAction(it).execute(axesNode)
            }
        }
    }

    /**
     * <combinationFilter>axis_label=='a'||axis_label=='b'</combinationFilter>
     */
    def combinationFilter(String filterExpression) {
        Preconditions.checkState(type == JobType.Matrix, 'combinationFilter can only be applied for Matrix jobs')

        withXmlActions << WithXmlAction.create { Node project ->
            def node = methodMissing('combinationFilter', filterExpression)
            project / node
        }
    }

    /**
     * <executionStrategy>
     *     <runSequentially>false</runSequentially>
     * </executionStrategy>
     */
    def runSequentially(boolean sequentially = true) {
        Preconditions.checkState(type == JobType.Matrix, 'runSequentially can only be applied for Matrix jobs')

        withXmlActions << WithXmlAction.create { Node project ->
            def node = methodMissing('runSequentially', sequentially)
            project / 'executionStrategy' / node
        }
    }

    /**
     * <executionStrategy>
     *     <touchStoneCombinationFilter>axis_label=='a'||axis_label=='b'</touchStoneCombinationFilter>
     *     <touchStoneResultCondition>
     *         <name>UNSTABLE</name>
     *         <ordinal>1</ordinal>
     *         <color>YELLOW</color>
     *         <completeBuild>true</completeBuild>
     *     </touchStoneResultCondition>
     * </executionStrategy>
     */
    def touchStoneFilter(String filter, boolean continueOnUnstable = false) {
        Preconditions.checkState(type == JobType.Matrix, 'touchStoneFilter can only be applied for Matrix jobs')

        withXmlActions << WithXmlAction.create { Node project ->
            project / 'executionStrategy' / 'touchStoneCombinationFilter'(filter)
            project / 'executionStrategy' / 'touchStoneResultCondition' {
                name continueOnUnstable ? 'UNSTABLE' : 'STABLE'
                color continueOnUnstable ? 'YELLOW' : 'BLUE'
                ordinal continueOnUnstable ? 1 : 0
            }
        }
    }

    /**
     * Specifies the path to the root POM.
     * @param rootPOM path to the root POM
     */
    def rootPOM(String rootPOM) {
        Preconditions.checkState(type == JobType.Maven, 'rootPOM can only be applied for Maven jobs')

        withXmlActions << WithXmlAction.create { Node project ->
            def node = methodMissing('rootPOM', rootPOM)
            project / node
        }
    }

    /**
     * Specifies the goals to execute.
     * @param goals the goals to execute
     */
    def goals(String goals) {
        Preconditions.checkState(type == JobType.Maven, 'goals can only be applied for Maven jobs')

        if (mavenGoals.empty) {
            withXmlActions << WithXmlAction.create { Node project ->
                def node = methodMissing('goals', this.mavenGoals.join(' '))
                project / node
            }
        }
        mavenGoals << goals
    }

    /**
     * Specifies the JVM options needed when launching Maven as an external process.
     * @param mavenOpts JVM options needed when launching Maven
     */
    def mavenOpts(String mavenOpts) {
        Preconditions.checkState(type == JobType.Maven, 'mavenOpts can only be applied for Maven jobs')

        if (this.mavenOpts.empty) {
            withXmlActions << WithXmlAction.create { Node project ->
                def node = methodMissing('mavenOpts', this.mavenOpts.join(' '))
                project / node
            }
        }
        this.mavenOpts << mavenOpts
    }

    /**
     * If set, Jenkins will send an e-mail notifications for each module, defaults to <code>false</code>.
     * @param perModuleEmail set to <code>true</code> to enable per module e-mail notifications
     */
    def perModuleEmail(boolean perModuleEmail) {
        Preconditions.checkState(type == JobType.Maven, 'perModuleEmail can only be applied for Maven jobs')

        withXmlActions << WithXmlAction.create { Node project ->
            def node = methodMissing('perModuleEmail', perModuleEmail)
            project / node
        }
    }

    /**
     * If set, Jenkins  will not automatically archive all artifacts generated by this project, defaults to
     * <code>false</code>.
     * @param archivingDisabled set to <code>true</code> to disable automatic archiving
     */
    def archivingDisabled(boolean archivingDisabled) {
        Preconditions.checkState(type == JobType.Maven, 'archivingDisabled can only be applied for Maven jobs')

        withXmlActions << WithXmlAction.create { Node project ->
            def node = methodMissing('archivingDisabled', archivingDisabled)
            project / node
        }
    }

    /**
     * Set to allow Jenkins to configure the build process in headless mode, defaults to <code>false</code>.
     * @param runHeadless set to <code>true</code> to run the build process in headless mode
     */
    def runHeadless(boolean runHeadless) {
        Preconditions.checkState(type == JobType.Maven, 'runHeadless can only be applied for Maven jobs')

        withXmlActions << WithXmlAction.create { Node project ->
            def node = methodMissing('runHeadless', runHeadless)
            project / node
        }
    }

    /**
     * <localRepository class="hudson.maven.local_repo.PerJobLocalRepositoryLocator"/>
     *
     * Set to use isolated local Maven repositories.
     * @param location the local repository to use for isolation
     */
    def localRepository(MavenContext.LocalRepositoryLocation location) {
        Preconditions.checkState(type == JobType.Maven, 'localRepository can only be applied for Maven jobs')
        Preconditions.checkNotNull(location, 'localRepository can not be null')

        withXmlActions << WithXmlAction.create { Node project ->
            def node = methodMissing('localRepository', [class: location.type])
            project / node
        }
    }

    def preBuildSteps(@DelegatesTo(value = StepContext, strategy = DF) Closure preBuildClosure) {
        Preconditions.checkState(type == JobType.Maven, 'prebuildSteps can only be applied for Maven jobs')

        StepContext preBuildContext = new StepContext(jobManagement)
        ContextHelper.executeInContext(preBuildClosure, preBuildContext)

        withXmlActions << WithXmlAction.create { Node project ->
            preBuildContext.stepNodes.each {
                project / 'prebuilders' << it
            }
        }
    }

    def postBuildSteps(@DelegatesTo(value = StepContext, strategy = DF) Closure postBuildClosure) {
        Preconditions.checkState(type == JobType.Maven, 'postBuildSteps can only be applied for Maven jobs')

        StepContext postBuildContext = new StepContext(jobManagement)
        ContextHelper.executeInContext(postBuildClosure, postBuildContext)

        withXmlActions << WithXmlAction.create { Node project ->
            postBuildContext.stepNodes.each {
                project / 'postbuilders' << it
            }
        }
    }

    def mavenInstallation(String name) {
        Preconditions.checkState(type == JobType.Maven, 'mavenInstallation can only be applied for Maven jobs')
        Preconditions.checkNotNull(name, 'name can not be null')

        withXmlActions << WithXmlAction.create { Node project ->
            project / 'mavenName'(name)
        }
    }

    def providedSettings(String settingsName) {
        String settingsId = jobManagement.getConfigFileId(ConfigFileType.MavenSettings, settingsName)
        Preconditions.checkNotNull(settingsId, "Managed Maven settings with name '${settingsName}' not found")

        withXmlActions << WithXmlAction.create { Node project ->
            project / settings(class: 'org.jenkinsci.plugins.configfiles.maven.job.MvnSettingsProvider') {
                settingsConfigId(settingsId)
            }
        }
    }

    Node getNode() {
        Node project = templateName == null ? executeEmptyTemplate() : executeUsing()

        executeWithXmlActions(project)

        project
    }

    void executeWithXmlActions(final Node root) {
        // Create builder, based on what we already have
        withXmlActions.each { WithXmlAction withXmlClosure ->
            withXmlClosure.execute(root)
        }
    }

    private executeUsing() {
        String configXml
        try {
            configXml = jobManagement.getConfig(templateName)
            if (configXml == null) {
                throw new JobConfigurationNotFoundException()
            }
        } catch (JobConfigurationNotFoundException jcnfex) {
            throw new JobTemplateMissingException(templateName)
        }

        def templateNode = new XmlParser().parse(new StringReader(configXml))

        if (type != getJobType(templateNode)) {
            throw new JobTypeMismatchException(name, templateName)
        }

        templateNode
    }

    private executeEmptyTemplate() {
        new XmlParser().parse(new StringReader(getTemplate(type)))
    }

    private String getTemplate(JobType type) {
        switch (type) {
            case JobType.Freeform: return emptyTemplate
            case JobType.BuildFlow: return emptyBuildFlowTemplate
            case JobType.Maven: return emptyMavenTemplate
            case JobType.Multijob: return emptyMultijobTemplate
            case JobType.Matrix: return emptyMatrixJobTemplate
        }
    }

    /**
     * Determines the job type from the given config XML.
     */
    private static JobType getJobType(Node node) {
        def nodeElement = node.name()
        JobType.values().find { it.elementName == nodeElement }
    }

    def emptyTemplate = '''<?xml version='1.0' encoding='UTF-8'?>
<project>
  <actions/>
  <description></description>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <scm class="hudson.scm.NullSCM"/>
  <canRoam>true</canRoam>
  <disabled>false</disabled>
  <blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding>
  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
  <triggers class="vector"/>
  <concurrentBuild>false</concurrentBuild>
  <builders/>
  <publishers/>
  <buildWrappers/>
</project>
'''

    def emptyBuildFlowTemplate = '''<?xml version='1.0' encoding='UTF-8'?>
<com.cloudbees.plugins.flow.BuildFlow>
  <actions/>
  <description></description>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <scm class="hudson.scm.NullSCM"/>
  <canRoam>true</canRoam>
  <disabled>false</disabled>
  <blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding>
  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
  <triggers class="vector"/>
  <concurrentBuild>false</concurrentBuild>
  <builders/>
  <publishers/>
  <buildWrappers/>
  <icon/>
  <dsl></dsl>
</com.cloudbees.plugins.flow.BuildFlow>
'''

    def emptyMavenTemplate = '''<?xml version='1.0' encoding='UTF-8'?>
<maven2-moduleset>
  <actions/>
  <description></description>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <scm class="hudson.scm.NullSCM"/>
  <canRoam>true</canRoam>
  <disabled>false</disabled>
  <blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding>
  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
  <triggers class="vector"/>
  <concurrentBuild>false</concurrentBuild>
  <aggregatorStyleBuild>true</aggregatorStyleBuild>
  <incrementalBuild>false</incrementalBuild>
  <perModuleEmail>false</perModuleEmail>
  <ignoreUpstremChanges>true</ignoreUpstremChanges>
  <archivingDisabled>false</archivingDisabled>
  <resolveDependencies>false</resolveDependencies>
  <processPlugins>false</processPlugins>
  <mavenValidationLevel>-1</mavenValidationLevel>
  <runHeadless>false</runHeadless>
  <publishers/>
  <buildWrappers/>
</maven2-moduleset>
'''

    def emptyMultijobTemplate = '''<?xml version='1.0' encoding='UTF-8'?>
<com.tikal.jenkins.plugins.multijob.MultiJobProject plugin="jenkins-multijob-plugin@1.8">
  <actions/>
  <description/>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <scm class="hudson.scm.NullSCM"/>
  <canRoam>true</canRoam>
  <disabled>false</disabled>
  <blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding>
  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
  <triggers class="vector"/>
  <concurrentBuild>false</concurrentBuild>
  <builders/>
  <publishers/>
  <buildWrappers/>
</com.tikal.jenkins.plugins.multijob.MultiJobProject>
'''

    def emptyMatrixJobTemplate = '''<?xml version='1.0' encoding='UTF-8'?>
<matrix-project>
  <description/>
  <keepDependencies>false</keepDependencies>
  <properties/>
  <scm class="hudson.scm.NullSCM"/>
  <canRoam>true</canRoam>
  <disabled>false</disabled>
  <blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding>
  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
  <triggers class="vector"/>
  <concurrentBuild>false</concurrentBuild>
  <axes/>
  <builders/>
  <publishers/>
  <buildWrappers/>
  <executionStrategy class="hudson.matrix.DefaultMatrixExecutionStrategyImpl">
    <runSequentially>false</runSequentially>
  </executionStrategy>
</matrix-project>
'''
}
