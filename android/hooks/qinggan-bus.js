/** The observed sticky vehicle-data refresh and OEM power-hold event. */
export class QingganBus {
    constructor(Java, application) {
        this.Java = Java;
        this.application = application;
    }

    refreshVehicleData(preflight = false) {
        const Event = this.Java.use('com.qinggan.bus.QGBusEvent');
        const event = Event.$new();
        event.setSticky(true);
        event.setEventType('com.qinggan.update.vehicle_data');
        const data = this.Java.use('android.os.Bundle').$new();
        data.putBoolean('update_vehicle_data', true);
        data.putLong('update_vehicle_time', this.Java.use('android.os.SystemClock').uptimeMillis());
        event.setData(data);
        const bus = this.connect();
        if (!preflight) bus.publish(event);
    }

    openPowerHold() {
        const event = this.Java.use('com.qinggan.bus.QGBusEvent').$new();
        event.setEventType('qg_action_start_power_hold_mode');
        this.connect().publish(event);
    }

    connect() {
        return this.Java.use('com.qinggan.bus.QGBus').$new(this.application);
    }
}
