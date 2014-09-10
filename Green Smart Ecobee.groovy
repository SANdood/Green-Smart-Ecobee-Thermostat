/**
 *  Green Thermostat
 *
 *  Author: lailoken@gmail.com
 *  Date: 2014-05-28
 */

definition(
  name: "Green Smart Ecobee Thermostat",
  namespace: "My Apps",
  author: "Barry A. Burke",
  description: "Try and save power by using the 4 base Climates (Programs) support the Hello Home mode changes. Also, if humidity gets too high during Cool season, drop temp back to \"Home\" settings so that it can overcool.",
  category: "Green Living",
  iconUrl: "https://s3.amazonaws.com/smartapp-icons/Meta/temp_thermo.png",
  iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Meta/temp_thermo@2x.png",
)

preferences {
	section("Optimize which Ecobee") {
    	input "ecobeeThermostat", "capability.thermostat", title: "Ecobee Thermostat", multiple: false, required: true
  	}
  	section("Morning Ends") {
    	input "morningEnds", "time", title: "Time?", required: true
   	}
    section("Humidity Management") {
    	input "humidityMgmt", "bool", title: "Manage humidity while away/sleep?", default: true
        input "setHumidity", "number", title: "Target humidity?", required: false
    }
}
        
def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
    unschedule()
	initialize()
}

def initialize() {
	log.debug "Initilizing GSET"
    
	subscribe(location, changedLocationMode)
    subscribe(ecobeeThermostat, "humidity", humidityHandler, [filterEvents: false])
    subscribe(ecobeeThermostat, "programScheduleName", climateChangeHandler)
	subscribe(app)

	state.rhOverride = false
	ecobeeThermostat.poll()
    ecobeeThermostat.setBacklightOffDuringSleep( 'true' )

//    schedule("0 2 3 * * ?", "thermoResume")					// ResumeProgram at 3:02AM every day so that autorecovery works    

    humidityHandler( null )  // Don't wait for the first Humidity change
}

def thermoResume() {

	if ( location.mode != "Away" ) {
		log.debug "Resume Night Program"
      	ecobeeThermostat.resumeProgram()         
    }
}

def changedLocationMode(evt)
{
 	log.debug "changedLocationMode: ${evt}, ${settings}"

  	if ( evt.value == "Home" ) {
    	def endTime=timeToday(morningEnds)
        
        if (now() < endTime.time) {
        	log.debug "Awake"
        	ecobeeThermostat.awake()
            sendNotificationEvent("And I set ${ecobeeThermostat} to the Awake program.")
        } else {
        	log.debug "Home"
            ecobeeThermostat.home()
            sendNotificationEvent("And I set ${ecobeeThermostat} to the Home program.")
        }

  	} 
  	else if ( evt.value == "Away" ) {
        log.debug "Away"
		ecobeeThermostat.away()
        sendNotificationEvent("And I set ${ecobeeThermostat} to the Away program.")
  	}
  	else if ( evt.value == "Night" ) {
    	log.debug "Night"
		ecobeeThermostat.goSleep()
        sendNotificationEvent("And I set ${ecobeeThermostat} to the Sleep program.")
  	}

  	ecobeeThermostat.poll()
    log.debug "Exit changedLocationMode"
}

def appTouch(evt)
{
	log.info "appTouch: $evt, $settings"

	//thermostat.setHeatingSetpoint(heatingSetpoint)
	//thermostat.setCoolingSetpoint(coolingSetpoint)
	ecobeeThermostat.poll()
}

// catchall
def event(evt)
{
	log.info "value: $evt.value, event: $evt, settings: $settings, handlerName: ${evt.handlerName}"
	ecobeeThermostat.poll()
}



def humidityHandler(evt) {
 
	if ( !humidityMgmt ) {
   		log.debug "Dehumidification management not enabled, returning"
//        ecobeeThermostat.poll()
        return
    }
    
    def tMode = ecobeeThermostat.latestValue("thermostatMode")   
	if (tMode != "cool") {
   		log.debug "Not in cool mode, returning: ${tMode}"
//        ecobeeThermostat.poll()
	 	return
	}
    
    def tProgram = ecobeeThermostat.latestValue("programType")
    if ( (tProgram == "vacation") || (tProgram == "quickSave") ) {
    	log.debug "Thermostat is in ${tProgram} mode, returning"
//        ecobeeThermostat.poll()
        return
    }
    
    def inOverride = state.rhOverride
    def tProgName = ecobeeThermostat.latestValue("programScheduleName")
    if ( !inOverride ) {
    	if (tProgram == "hold") {
        	log.debug "Not in override, but in hold mode - returning"
            return
        }
        else if ( (tProgName != "Away") && (tProgName != "Sleep") ) {
    		log.debug "Thermostat is in ${tProgName} mode, returning"
//        	ecobeeThermostat.poll()
        	return
        }
    }  

    
// OK, we are enabled, cooling, and running Away or Sleep climates...let's work on the humidity.

    Double humidityNum   
	if (evt != null) {
//		log.debug "Humidity: $evt.value, $evt.unit, $evt.name, $evt"
    
 	   	humidityNum = Double.parseDouble(evt.value.replace("%", "")) // if a humidity event, use reported value
    }
    else {
    	humidityNum = ecobeeThermostat.latestValue("humidity") as Double  // if not, just double check prior value
 	}
    
    double targetHumidity = setHumidity	as Double  		// Default to specified setting, if provided
    double ecobeeDehumidLevel = ecobeeThermostat.latestValue("dehumidifierLevel") as Double
    if (targetHumidity == null) {
    	if (ecobeeDeumidLevel != null) {
    		targetHumidity = ecobeeDehumidLevel	+ 1 	// Ecobee settings            
    	}
    	else {
    		targetHumidity=50 as Double					// Default value
        }
    }
    if ((ecobeeDehumidLevel != null) && (targetHumidity <= ecobeeDehumidLevel)) targetHumidity = ecobeeDehumidLevel + 1
      
    log.debug "Target ${targetHumidity}, reported ${humidityNum}"
    
	if ( inOverride == false ) {    
    	if ( (location.mode!="Home") && (humidityNum > targetHumidity) ) {
    		log.debug "Humidity (${humidityNum}) is higher than target (${targetHumidity}) - switch to Home climate for a while"
    		ecobeeThermostat.home()
       		state.rhOverride = true
  		}
	}
    else {
    	if ((ecobeeDehumidLevel != null) && (humidityNum > ecobeeDehumidLevel)) {
//        	ecobeeThermostat.poll()
        	return   // not done yet
    	}
        if (humidityNum <= targetHumidity) {		// looks like we finished our override!
    		log.debug "Returning to previously scheduled program ${location.mode}"
    		ecobeeThermostat.resumeProgram()
        	state.rhOverride = false
        }
    }
//    ecobeeThermostat.poll()
}


// If Ecobee climate changes to "Away" while the house things someone is still home, reset the slimate back to "Home"
// This should only happen if someone stays home to work in the morning, past the time that the Ecobee program switches
// on it's own.

def climateChangeHandler( evt ) {

	if (evt != null) {
		log.debug "Climate Change: $evt.value, $evt.unit, $evt.name, $evt"
        
        if ( (evt.value == "Away") && (location.mode == "Home") ) {
        	log.debug "Returning Ecobee ${ecobeeThermostat.name} to Home program"
        	ecobeeThermostat.home()
        }
	}
    ecobeeThermostat.poll()
}
