import catalog from '../app/src/main/assets/voice/verified-commands.json';

export function approvedVoiceCommands(Java) {
    const Build = Java.use('android.os.Build');
    const product = String(Build.PRODUCT.value);
    const fingerprint = String(Build.FINGERPRINT.value);
    const sdk = Java.use('android.os.Build$VERSION').SDK_INT.value;
    const abi = String(Build.SUPPORTED_ABIS.value[0]);
    if (![28, 30].includes(sdk) || abi !== 'arm64-v8a' || !/^[A-Za-z0-9_.:/+-]{1,256}$/.test(fingerprint) ||
        catalog.schema !== 1 || !Array.isArray(catalog.headUnits) || catalog.headUnits.length > 128) return null;
    const matches = catalog.headUnits.filter(unit => unit && unit.product === product &&
        unit.fingerprint === fingerprint && unit.sdk === sdk && unit.abi === abi);
    if (matches.length !== 1) return null;
    const {profile, commands} = matches[0];
    if (typeof profile !== 'string' || profile === 'UNKNOWN' || !/^[A-Z_]+$/.test(profile) ||
        !Array.isArray(commands) || commands.length < 1 || commands.length > 256 ||
        commands.some(id => typeof id !== 'string' || !/^[A-Za-z][A-Za-z0-9]{0,63}$/.test(id)) ||
        new Set(commands).size !== commands.length) return null;
    return {profile, commands: new Set(commands)};
}

export class VoiceCommandFailure extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

export function requireStationary(manager, now) {
    const started = now();
    let gear, speed;
    try {
        const gearStatus = manager.getGearStatus();
        gear = gearStatus === null ? '' : String(gearStatus.name());
        speed = manager.getVehicleSpeed();
    } catch (_) {
        throw new VoiceCommandFailure('blocked', 'Vehicle state unavailable');
    }
    const finished = now();
    if (!Number.isSafeInteger(started) || !Number.isSafeInteger(finished) || finished < started ||
        finished - started > 500 || !gear || gear === 'Unknown' || !Number.isInteger(speed) || speed < 0) {
        throw new VoiceCommandFailure('blocked', 'Vehicle state unavailable');
    }
    if (gear !== 'Parking') throw new VoiceCommandFailure('blocked', 'This command requires Parking gear');
    if (speed !== 0) throw new VoiceCommandFailure('blocked', 'This command requires zero vehicle speed');
}
