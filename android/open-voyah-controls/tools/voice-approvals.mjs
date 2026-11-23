#!/usr/bin/env node
// Writes the voice-command approval catalog that the launcher agent is compiled with.
// It is the shipped catalog, plus the owner's own rows when OVC_LOCAL_VOICE_APPROVALS names a
// local-vehicle verified-commands.json. Gradle sets that variable only for a local-vehicle build and
// refuses to package such a build as release. The agent and the app still check every row at run time.
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const shipped = path.join(root, 'app/src/main/assets/voice/verified-commands.json');
const fingerprint = /^[A-Za-z0-9_.:/+,-]{1,256}$/;
const command = /^[A-Za-z][A-Za-z0-9]{0,63}$/;

export function readCatalog(file) {
    let catalog;
    try {
        catalog = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (error) {
        throw new Error(`${file}: ${error.message}`);
    }
    if (catalog?.schema !== 1 || !Array.isArray(catalog.headUnits)) throw new Error(`${file}: expected {"schema":1,"headUnits":[...]}`);
    catalog.headUnits.forEach((unit, index) => {
        const where = `${file}: headUnits[${index}]`;
        if (typeof unit?.product !== 'string' || !fingerprint.test(unit.fingerprint ?? '') ||
            ![28, 30].includes(unit.sdk) || unit.abi !== 'arm64-v8a' || typeof unit.profile !== 'string')
            throw new Error(`${where} needs product, fingerprint, sdk 28 or 30, abi arm64-v8a and profile`);
        if (!Array.isArray(unit.commands) || unit.commands.length < 1 || unit.commands.length > 256 ||
            unit.commands.some(id => typeof id !== 'string' || !command.test(id)) ||
            new Set(unit.commands).size !== unit.commands.length)
            throw new Error(`${where} needs 1-256 distinct command identifiers`);
    });
    return catalog;
}

export function merge(base, local) {
    const key = unit => [unit.product, unit.fingerprint, unit.sdk, unit.abi].join('\t');
    const seen = new Set(base.headUnits.map(key));
    for (const unit of local.headUnits) {
        // Two rows for one head unit would make both the app and the agent refuse it; say so at build time.
        if (seen.has(key(unit))) throw new Error(`Head unit ${unit.product} ${unit.fingerprint} is listed more than once`);
        seen.add(key(unit));
    }
    const headUnits = [...base.headUnits, ...local.headUnits];
    if (headUnits.length > 128) throw new Error('The voice approval catalog holds at most 128 head units');
    return {schema: 1, headUnits};
}

function main() {
    const output = process.argv[2];
    if (!output) throw new Error('usage: voice-approvals.mjs <output.json>');
    const localFile = process.env.OVC_LOCAL_VOICE_APPROVALS || '';
    const catalog = localFile ? merge(readCatalog(shipped), readCatalog(localFile)) : readCatalog(shipped);
    fs.mkdirSync(path.dirname(output), {recursive: true});
    fs.writeFileSync(output, JSON.stringify(catalog, null, 2) + '\n');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        main();
    } catch (error) {
        console.error(error.message);
        process.exit(1);
    }
}
