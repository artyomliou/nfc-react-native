# NfcReactNative

nfc-react-native is a react-native module for android to write/read Mifare Classic (NFC) tags.  

The changes between original repo and this one are:
1. Use async/await on API
2. Faster

## Installation

```
$ npm install artyomliou/nfc-react-native --save
```
or
```
$ yarn add artyomliou/nfc-react-native
```

then, 
```bash
$ react-native link nfc-react-native
```

## Usage

### Component
```javascript
import React, { Component } from 'react';
import { View, Text, Button } from 'react-native';
import MifareClassicReader from './MifareClassicReader';

export default class NfcSample extends Component {

  state = {
    tagId: '',
    read: '',
    wrote: ''
  };

  render() {
    return (
      <View style={styles.container}>
        <Text>
          { this.state.tagId }
        </Text>
        <Text>
          { this.state.read }
        </Text>
        <Text>
          { this.state.wrote }
        </Text>
        <Button
          onPress={this.onPressButton}
          title="read something"
          style={{ flex: 1 }}
        />
      </View>
    );
  }
  
  componentDidMount() {
    MifareClassicReader.setKey('FFFFFFFFFFFF', 'B');
    MifareClassicReader.registerEvents(this.onTagDetected, onMifareClassicOperationError);
  }
  
  onTagDetected = (event) => {
    const { id, timeout, type, size } = event;
    console.log(id, timeout, type, size);
    
    this.setState({
      tagId: id
    });
  }
  
  onMifareClassicOperationError = (error) => {
    console.log(error);
  }
  
  onPressButton = async (event) => {
   try {
     const dataRead = await MifareReader.readBlock({
      sector: 1,
      block: 1
    });
    if (dataRead.payload) {
      // payload represent byte[] in hex string
      this.setState({
        read: dataRead.payload
      });
    }
    
    const dataWrote = await MifareReader.writeByte({
      sector: 1,
      block: 1,
      byte: 5,
      data: 'A'
    });
    if (dataWrote.payload) {
      this.setState({
        wrote: dataWrote.payload
      });
    }
   } catch (error) {
     console.log(error);
   }
  }
}
```


### MifareClassicReader
```javascript
import { DeviceEventEmitter } from 'react-native';
import { setKey, read, writeByte, close } from 'nfc-react-native';

export default class MifareClassicReader {

  /**
   * 
   * @param {function} onTagDetected 
   * @param {function} onTagError 
   */
  registerEvents(onTagDetected, onTagError) {
    DeviceEventEmitter.addListener('onTagError', onTagError);
    DeviceEventEmitter.addListener('onTagDetected', onTagDetected);
  }
  
  /**
   * 
   * @param {string} key 
   * @param {string} type either 'A' or 'B'
   */
  setKey = (key, type) => setKey(key, type);

  /**
   * @param {object} command
   * @return {Promise}
   */
  writeByte = (command) => writeByte(command);

  /**
   * @param {object} command
   * @return {Promise}
   */
  readBlock = (command) => read(command);

  /**
   * @return {Promise}
   */
  close = () => close();
}

```

## Configuration

### android/app/src/main/AndroidManifest.xml

Add belows

```xml
<uses-permission android:name="android.permission.NFC"/>
<uses-feature android:name="android.hardware.nfc" android:required="true" />
```
  
Also these,
inside your **MainActivity**.
```xml
<activity
  android:name=".MainActivity"
  android:launchMode="singleTask"
  android:label="@string/app_name"
  android:configChanges="keyboard|keyboardHidden|orientation|screenSize">

  <!-- THIS -->
  <intent-filter>
    <action android:name="android.nfc.action.TECH_DISCOVERED"/>
  </intent-filter>

  <!-- and THIS -->
  <meta-data android:name="android.nfc.action.TECH_DISCOVERED"
             android:resource="@xml/nfc_tech_filter" />
</activity>
```

### android/app/src/main/res/nfc_tech_filter.xml
Add a xml file with contents:
```xml
<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
  <tech-list>
    <tech>android.nfc.tech.MifareClassic</tech>
  </tech-list>
</resources>
```

## Contribution
Contributions are welcome :raised_hands:

## License
This repository is distributed under [MIT license](https://github.com/Lube/nfc-react-native/blob/master/LICENSE) 
