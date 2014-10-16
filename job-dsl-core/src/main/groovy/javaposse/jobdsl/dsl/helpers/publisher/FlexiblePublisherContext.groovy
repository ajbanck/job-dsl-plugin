package javaposse.jobdsl.dsl.helpers.publisher

import javaposse.jobdsl.dsl.JobManagement
import javaposse.jobdsl.dsl.helpers.ContextHelper
import javaposse.jobdsl.dsl.helpers.Context
import javaposse.jobdsl.dsl.helpers.step.RunConditionContext
import javaposse.jobdsl.dsl.helpers.step.StepContext
import javaposse.jobdsl.dsl.helpers.step.condition.AlwaysRunCondition
import javaposse.jobdsl.dsl.helpers.step.condition.RunCondition
import javaposse.jobdsl.dsl.helpers.step.condition.RunConditionFactory

class FlexiblePublisherContext implements Context {
    private final JobManagement jobManagement

    RunCondition condition = new AlwaysRunCondition()
    Node action

    FlexiblePublisherContext(JobManagement jobManagement) {
        this.jobManagement = jobManagement
    }

    def condition(@DelegatesTo(RunConditionContext) Closure closure) {
        condition = RunConditionFactory.of(closure)
    }

    def step(@DelegatesTo(StepContext) Closure closure) {
        StepContext stepContext = new StepContext(jobManagement)
        ContextHelper.executeInContext(closure, stepContext)
        if (stepContext.stepNodes.size() > 0) {
            action = stepContext.stepNodes[0]
        }
    }

    def publisher(@DelegatesTo(PublisherContext) Closure closure) {
        PublisherContext publisherContext = new PublisherContext(jobManagement)
        ContextHelper.executeInContext(closure, publisherContext)
        if (publisherContext.publisherNodes.size() > 0) {
            action = publisherContext.publisherNodes[0]
        }
    }
}
