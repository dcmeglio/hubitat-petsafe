/**
 *  Petsafe Integration
 *
 *  Copyright 2020 Dominick Meglio
 *
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

definition(
	name: "Petsafe Integration",
	namespace: "dcm.petsafe",
	author: "Dominick Meglio",
	description: "Connects to Petsafe SmartFeeders",
	category: "My Apps",
	iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
	iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	documentationLink: "https://github.com/dcmeglio/hubitat-petnet/blob/master/README.md")


preferences {
	page(name: "prefMain", title: "Petsafe")
	page(name: "prefAccount", title: "Petsafe")
	page(name: "prefCode", title: "Petsafe Code")
	page(name: "prefDevices", title: "Devices")
	page(name: "prefMessages", title: "", install: false, uninstall: false, nextPage: "prefAccount")
}

def prefMain() {
	def nextPage = "prefDevices"
	if (petsafeEmail == null)
		nextPage = "prefAccount"
	return dynamicPage(name: "prefMain", title: "Petsafe", nextPage:nextPage, uninstall:false, install: false) {
		section("App Name"){
			label title: "Enter a name for this app (optional)", required: false
		}
		if (petsafeEmail != null) {
			section {
				href(name: "prefAccount", title: "Account Information", required: false, page: "prefAccount", description: "Update your account information")
			}
		}
	}
}

def prefAccount() {
	return dynamicPage(name: "prefAccount", title: "Connect to Petsafe", nextPage:"prefCode", uninstall:false, install: false) {
		section {
			input "petsafeEmail", "email", title: "Petsafe Email", description: "Petsafe Email", required: true
		}
	}
}

def prefCode() {
	apiAuthenticate()
	return dynamicPage(name :"prefCode", title: "Code", nextPage: "prefDevices", uninstall: false, install: false) {
		section {
		   paragraph "In a minute you should receive an email with a 6 digit code. Enter the code below including the hyphen."
		   input "petsafeCode", "string", title: "PIN code", description: "Petsafe PIN code", required: true
		}
	}
}

def prefDevices() {
	apiGetTokens()
	def feeders = apiGetFeeders()

	state.feeders = [:]
	for (feeder in feeders) {
		state.feeders[feeder.thing_name] = feeder.settings.friendly_name
	}
	return dynamicPage(name :"prefDevices", title: "Devices", nextPage: "prefMessages", uninstall: false, install: false) {
		section {
		   input "smartfeeders", "enum", title: "Smart Feeders", options: state.feeders, multiple: true, required: true
		}
	} 
}

def prefMessages() {
	dynamicPage(name: "prefMessages", title: "", install: true, uninstall: true) {
		section {
			input "enableNotifications", "bool", title: "Enable notifications?",defaultValue: false, displayDuringSetup: true, submitOnChange: true
		}
		if (enableNotifications)
		{
			section {
				input "notificationDevices", "capability.notification", title: "Device(s) to notify?", required: true, multiple: true
			}
			section("Notification Messages:") {
				paragraph "<b><u>Instructions to use variables:</u></b>"
				paragraph "%device% = Pet feeder device's name<br><hr>"
				input "messageFeedingOccurred", "text", title: "Feeding Occurred:", required: false, defaultValue:"%device% has completed a feeding", submitOnChange: true 
				input "messageHopperLow", "text", title: "Hopper Low:", required: false, defaultValue:"%device% is low on food", submitOnChange: true 
				input "messageHopperEmpty", "text", title: "Hopper Empty:", required: false, defaultValue:"%device% is empty", submitOnChange: true 
				input "messageErrorOccurred", "text", title: "Error Occurred:", required: false, defaultValue: "An error occurred feeding from %device%", submitOnChange: true 
			}
		}
	}
}

@Field apiUrl = "https://api.ps-smartfeed.cloud.petsafe.net/api/v2/"
@Field apiUserUrl = "https://users-api.ps-smartfeed.cloud.petsafe.net/users/"

def installed() {
	logDebug "Installed with settings: ${settings}"
	state.lastMessageCheck = new Date()
	initialize()
}

def updated() {
	logDebug "Updated with settings: ${settings}"
	unschedule()
	unsubscribe()
	initialize()
}

def uninstalled() {
	logDebug "Uninstalled app"

	for (device in getChildDevices())
	{
		deleteChildDevice(device.deviceNetworkId)
	}    
}

def initialize() {
	logDebug "initializing"

	for (feeder in smartfeeders) {
		if (!getChildDevice("petsafe:" + feeder)) {
			addChildDevice("dcm.petsafe", "PetSafe Feeder", "petsafe:" + feeder, 1234, ["name": state.feeders[feeder], isComponent: false])
		}
	}

	schedule("0 */1 * * * ? *", refreshDevices)
}

def refreshDevices() {
	def feederData = apiGetFeeders()
	for (feeder in feederData) {
		def childFeeder = getChildDevice("petsafe:" + feeder.thing_name)
		if (childFeeder != null) {
			if (feeder.is_adapter_installed)
				childFeeder.sendEvent(name: "powerSource", value: "dc")
			if (feeder.is_batteries_installed) {
				if (!feeder.is_adapter_installed)
					childFeeder.sendEvent(name: "powerSource", value: "battery")
				
				def voltage = feeder.battery_voltage.toInteger()
				if (voltage >= 100) {
					if (voltage > 29100)
						childFeeder.sendEvent(name: "battery", value: 100)
					else {
						childFeeder.sendEvent(name: "battery", value: (int)(((voltage - 26000)/(29100-26000))*100))
					}
				}
			}

			childFeeder.sendEvent(name: "slow_feed", value: feeder.settings.slow_feed)
			childFeeder.sendEvent(name: "child_lock", value: feeder.settings.child_lock)
			childFeeder.sendEvent(name: "schedule_enabled", value: !feeder.settings.paused)
			def feedingSchedule = []
			feeder.schedules.each { feedingSchedule << [ time: it.time, amount: it.amount/8]}
			childFeeder.sendEvent(name: "feeding_schedule", value: feedingSchedule)

			def recentFeedings = apiGetRecentFeedings(feeder.thing_name)
			def epochNow = (int)(now()/1000)
			if (state.lastMessageCheck.toString().contains("T") || state.lastMessageCheck > 9999999999)
				state.lastMessageCheck = epochNow
			for (feeding in recentFeedings) {
				def msgEpoch = feeding.payload?.time ?: (int)(Date.parse("yyyy-MM-dd HH:mm:ss", feeding.created_at, TimeZone.getTimeZone('UTC')).getTime()/1000)
				logDebug "Feeding Message: ${feeding.message_type} ${msgEpoch} ${state.lastMessageCheck}"

				if (msgEpoch > state.lastMessageCheck) {
					switch (feeding.message_type) {
						case "FEED_ERROR_MOTOR_CURRENT":
						case "FEED_ERROR_MOTOR_SWITCH":
							childFeeder.sendEvent(name: "consumableStatus", value: "maintenance_required")
							notifyError(childFeeder)
							break
						case "FEED_DONE":
							childFeeder.sendEvent(name: "lastFeedingTime", value: feeding.payload.time)
							notifyFeeding(childFeeder)
							break
						case "FOOD_GOOD":
							childFeeder.sendEvent(name: "hopperStatus", value: "full")
							childFeeder.sendEvent(name: "consumableStatus", value: "good")
							break
						case "FOOD_LOW":
							childFeeder.sendEvent(name: "hopperStatus", value: "low")
							childFeeder.sendEvent(name: "consumableStatus", value: "replace")
							notifyFoodLow(childFeeder)
							break
						case "FOOD_EMPTY":
							childFeeder.sendEvent(name: "hopperStatus", value: "empty")
							childFeeder.sendEvent(name: "consumableStatus", value: "missing")
							notifyFoodEmpty(childFeeder)
							break
						case "WILL_MESSAGE":
							break
					}
					state.lastMessageCheck = msgEpoch
				}
			}
		}
	}
}

def handleFeed(device, feedAmount, slowFeed) {
	def feeder = device.deviceNetworkId.replace("petsafe:","")
	def params = [
		uri: "${apiUrl}feeders/${feeder}/meals",
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"token": state.deprecatedToken
		],
		body: [
			"amount": feedAmount,
			"slow_feed": slowFeed ?: false
		]
	] 
	def result = null

	httpPost(params) { resp ->
		result = resp.data
	}
	return result
}

//[{time=6:30, amount=0.375}, {time=18:00, amount=0.375}]
def handleSchedule(device, schedule) {
	try
	{
		def scheduleJson =  new JsonSlurper().parseText(schedule) 
		scheduleJson.each {
			def t = timeToday(it.time)
			it.time = t.hours + ":" + t.minutes
			def cups = it.amount*8
			
			if (it.amount < 0.125 || it.amount > 1 || [1,2,3,4,5,6,7,8].find {v -> v == cups} == null) {
				log.error "Invalid schedule specified"
				return
			}
		}
	}
	catch (e) {
		log.error "Invalid schedule specified"
		return
	}
	
	def feeder = device.deviceNetworkId.replace("petsafe:","")
	def params = [
		uri: "${apiUrl}feeders/${feeder}/schedules",
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"token": state.deprecatedToken
		],
		body: [
			"amount": feedAmount,
			"slow_feed": slowFeed ?: false
		]
	] 
	def result = null

	httpGet(params) { resp ->
		for (currentFeeding in resp.data) {
			def newItem = scheduleJson.find { it.time == currentFeeding.time }
			if (newItem == null) {
				asynchttpDelete(null,[
					uri:"${apiUrl}feeders/${feeder}/schedules/${currentFeeding.id}",
					contentType: "application/json",
					requestContentType: "application/json",
					headers: [
						"token": state.deprecatedToken
					]
				])
			}
			else if (newItem != null && newItem.amount != currentFeeding.amount/8) {
				asynchttpPut(null,[
					uri:"${apiUrl}feeders/${feeder}/schedules/${currentFeeding.id}",
					contentType: "application/json",
					requestContentType: "application/json",
					headers: [
						"token": state.deprecatedToken
					],
					body: [
						amount: newItem.amount*8,
						time: newItem.time
					]
				])
			}
		}
		for (newItem in scheduleJson) {
			if (!resp.data.find { it.time == newItem.time}) {              
				asynchttpPost(null,[
					uri:"${apiUrl}feeders/${feeder}/schedules",
					contentType: "application/json",
					requestContentType: "application/json",
					headers: [
						"token": state.deprecatedToken
					],
					body: [
						amount: newItem.amount*8,
						time: newItem.time
					]
				])
			}
		}
	}
	return result
}

def notifyError(dev) {
	if (notificationDevices != null)
		notificationDevices*.deviceNotification(messageErrorOccurred.replace("%device%",dev.displayName))
}

def notifyFeeding(dev) {
	if (notificationDevices != null)
		notificationDevices*.deviceNotification(messageFeedingOccurred.replace("%device%",dev.displayName))
}

def notifyFoodLow(dev) {
	if (notificationDevices != null)
		notificationDevices*.deviceNotification(messageHopperLow.replace("%device%",dev.displayName))
}

def notifyFoodEmpty(dev) {
	if (notificationDevices != null)
		notificationDevices*.deviceNotification(messageHopperEmpty.replace("%device%",dev.displayName))
}

def apiAuthenticate() {
	def params = [
		uri: "${apiUserUrl}",
		contentType: "application/json",
		requestContentType: "application/json",
		body: [
			"consentVersion": "2019-06-25",
			"email": petsafeEmail,
			"language": "en"
		]
	] 
	def result = null

	httpPost(params) { resp ->
		result = resp.data
	}
	return result
}

def apiGetTokens() {
	def code = petSafeCode
	if (!(petsafeCode =~ /\d{3}-\d{3}/))
		code = petsafeCode[0..2] + "-" + petsafeCode[3..5]

	def params = [
		uri: "${apiUserUrl}tokens",
		contentType: "application/json",
		requestContentType: "application/json",
		body: [
			"code": code,
			"email": petsafeEmail
		]
	] 
	def result = null
	try
	{
		httpPost(params) { resp ->
			result = resp.data
			state.identityId = result.identityId
			state.accessToken = result.accessToken
			state.refreshToken = result.refreshToken
			state.deprecatedToken = result.deprecatedToken
			app.removeSetting("petsafeCode")
		}
	}
	catch (e) {
		log.error e
		return null
	}
	return result 
}

def apiGetFeeders() {
	def params = [
		uri: "${apiUrl}feeders",
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"token": state.deprecatedToken
		]
	]

	def result = null
	httpGet(params) { resp ->
		result = resp.data
	}
	return result
}

def apiGetRecentFeedings(feeder) {
		def params = [
		uri: "${apiUrl}feeders/${feeder}/messages?days=2",
		contentType: "application/json",
		requestContentType: "application/json",
		headers: [
			"token": state.deprecatedToken
		]
	]

	def result = null
	httpGet(params) { resp ->
		result = resp.data
	}
	return result
}

def logDebug(msg) {
	if (settings?.debugOutput) {
		log.debug msg
	}
}