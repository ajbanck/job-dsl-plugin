package javaposse.jobdsl.dsl.helpers.step

import javaposse.jobdsl.dsl.JobManagement
import javaposse.jobdsl.dsl.helpers.ContextHelper
import javaposse.jobdsl.dsl.helpers.Context

import static groovy.lang.Closure.DELEGATE_FIRST

class PhaseContext implements Context {
    private final JobManagement jobManagement

    String phaseName
    String continuationCondition

    List<PhaseJobContext> jobsInPhase = []

    PhaseContext(JobManagement jobManagement, String phaseName, String continuationCondition) {
        this.jobManagement = jobManagement
        this.phaseName = phaseName
        this.continuationCondition = continuationCondition
    }

    void phaseName(String phaseName) {
        this.phaseName = phaseName
    }

    void continuationCondition(String continuationCondition) {
        this.continuationCondition = continuationCondition
    }

    def job(String jobName,
            @DelegatesTo(value = PhaseJobContext, strategy = DELEGATE_FIRST) Closure phaseJobClosure = null) {
        job(jobName, true, true, phaseJobClosure)
    }

    def job(String jobName, boolean currentJobParameters,
            @DelegatesTo(value = PhaseJobContext, strategy = DELEGATE_FIRST) Closure phaseJobClosure = null) {
        job(jobName, currentJobParameters, true, phaseJobClosure)
    }

    def job(String jobName, boolean currentJobParameters, boolean exposedScm,
            @DelegatesTo(value = PhaseJobContext, strategy = DELEGATE_FIRST) Closure phaseJobClosure = null) {
        PhaseJobContext phaseJobContext = new PhaseJobContext(jobManagement, jobName, currentJobParameters, exposedScm)
        ContextHelper.executeInContext(phaseJobClosure, phaseJobContext)

        jobsInPhase << phaseJobContext

        phaseJobContext
    }
}
