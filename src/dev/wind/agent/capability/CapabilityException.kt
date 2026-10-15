package dev.wind.agent.capability

open class CapabilityException(message: String) : SecurityException(message)

class CapabilityDeniedException(message: String) : CapabilityException(message)

class CapabilityRevokedException(message: String) : CapabilityException(message)

class CapabilityExpiredException(message: String) : CapabilityException(message)
