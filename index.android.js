import React from 'react-native';

const NfcReactNative = React.NativeModules.NfcReactNative;

export const setKeys = NfcReactNative.setKeys;
export const connect = NfcReactNative.connect;
export const close = NfcReactNative.close;
export const read = NfcReactNative.read;
export const write = NfcReactNative.write;
