/**
 *  PetSafe Feeder
 *
 *  Copyright 2020 Dominick Meglio
 *
 */
metadata {
    definition (name: "PetSafe Feeder", namespace: "dcm.petsafe", author: "Dominick Meglio") {
		capability "Momentary"
		capability "PushableButton"
        capability "Battery"
        capability "PowerSource"
        capability "Consumable"

        attribute "hopperStatus", "enum"
        attribute "slow_feed", "bool"
        attribute "child_lock", "bool"
        attribute "schedule_enabled", "bool"
        attribute "feeding_schedule", "JSON_OBJECT"

        attribute "lastFeedingTime", "number"

        command "setSchedule", ["JSON_OBJECT"]
    }
}

preferences {
    input "feedAmount", "enum", title: "Feeding Amount (in Cups)" , options: [1:"1/8", 2:"1/4", 3: "3/8", 4: "1/2", 5: "5/8", 6: "3/4", 7: "7/8", 8: "1"]
    input "slowFeed", "bool", title: "Enable slow feeding for manual feeds?", defaultValue: false
}
def installed() {
	sendEvent(name: "numberOfButtons", value: "1")
}

def push() {
    parent.handleFeed(device, feedAmount, slowFeed)
}

def push(buttonNumber) {
    push()
}

def setSchedule(schedule) {
    parent.handleSchedule(device, schedule)
}