# hubitat-petsafe
Petsafe Smart Feeder Integration for Hubitat. 
 
## Apps
The Petsafe Integration app is what communicates with the Petsafe Cloud API.

### Configuration
To connect you will need to specify your Petsafe username. After entering it you will receive an email that has your 6 digit code from Petsafe. Enter this value in the app and you will be presented with your devices. The app will then find your Smart Feeder and create a device for it. 

### Set Schedule
You can use the `setSchedule` command to set the schedule via JSON. The format is:
```json
[
    {
        "time": "HH:mm",
        "amount": amount
    },
    ...
]
```

The time is specified in 24 hour format and the amount must between 0-1 in 8ths.
