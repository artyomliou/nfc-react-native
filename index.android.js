import React from 'react-native';

const NfcReactNative = React.NativeModules.NfcReactNative;

export const setKeys = NfcReactNative.setKeys;
export const read = NfcReactNative.read;
export const writeByte = NfcReactNative.writeByte;
