import React from 'react-native';

const NfcReactNative = React.NativeModules.NfcReactNative;

export const setKey = NfcReactNative.setKey;
export const read = NfcReactNative.read;
export const writeByte = NfcReactNative.writeByte;
export const close = NfcReactNative.close;
