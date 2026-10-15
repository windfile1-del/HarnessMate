package dev.wind.agent.capability

/** Small defense-in-depth facade for side-effecting integrations. */
class CapabilityGuard(private val broker: CapabilityBroker) {
    fun require(context: TaskContext, requirement: CapabilityRequirement): CapabilityLease =
        when (val result = broker.check(context, requirement)) {
            is CapabilityCheck.Allowed -> broker.verify(result.lease)
            is CapabilityCheck.NeedsApproval -> throw CapabilityDeniedException(
                "CAPABILITY_APPROVAL_REQUIRED: ${result.request.summary}"
            )
            is CapabilityCheck.Denied -> throw CapabilityDeniedException(result.reason)
        }

    fun requireCanonicalRead(context: TaskContext, mountIds: Set<String>): CapabilityLease =
        require(context, CapabilityRequirement.ProjectRead(context.projectId, mountIds))

    fun requireCanonicalWrite(context: TaskContext, mountIds: Set<String>): CapabilityLease =
        require(context, CapabilityRequirement.CanonicalWrite(context.projectId, mountIds))

    fun requireCanonicalDelete(context: TaskContext, mountIds: Set<String>): CapabilityLease =
        require(context, CapabilityRequirement.CanonicalDelete(context.projectId, mountIds))

    fun requireExecution(context: TaskContext): CapabilityLease =
        require(context, CapabilityRequirement.Execution(context.projectId))
}
