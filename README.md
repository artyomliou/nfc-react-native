# NfcReactNative

nfc-react-native is a react-native module for android to write/read Mifare Classic (NFC) tags.  

The difference between original repo and this one is:
1. Async/await API
2. Cancel all operations, if any one fails.
3. Start a sequence of operations by **event**, not button

## Getting started

`$ npm install bros0215/nfc-react-native --save`

### Mostly automatic installation

`$ react-native link nfc-react-native`

## Usage
```javascript
import { setKey, read, writeByte } from 'nfc-react-native'

...
export default class NfcSample extends Component {

  setAuthKey(key, type) {
    setKey(key, type);
  }

  componentDidMount() {
    DeviceEventEmitter.addListener('onTagError', function (e) {
        console.log('error', e)
        Alert.alert(JSON.stringify(e))
    })

    DeviceEventEmitter.addListener('onTagDetected', async function (event) {
      console.log('detected', event);

      try {
        for (let block = 0; block < 4; block++) {
          let sector = 1;
          let response = await read({ sector, block });
          console.log(response.payload);
        }
        for (let byte = 0; byte < 6; byte++) {
          let sector = 1;
          let block = 1;
          console.log(await writeByte({ sector, block, byte, data: '1' }));
        }
      } catch (error) {
        console.log('error', error);
      }
    });
  }

  render() {
    return (
      <View style={styles.container}>
        <Text style={styles.welcome}>
          Welcome to React Native!
        </Text>
      </View>
    );
  }
}
...
```

## Configuration

In your manifest add:
```xml
<uses-permission android:name="android.permission.NFC" />
....
```
Your main activity should look like
```xml
...
<activity
  android:name=".MainActivity"
  android:launchMode="singleTask"
  android:label="@string/app_name"
  android:configChanges="keyboard|keyboardHidden|orientation|screenSize">

  <intent-filter>
    <action android:name="android.nfc.action.TECH_DISCOVERED"/>
  </intent-filter>

  <meta-data android:name="android.nfc.action.TECH_DISCOVERED"
             android:resource="@xml/nfc_tech_filter" />
</activity>
```

Add a xml file in android/app/src/main/res folder (create if it doesn't exist) with the following:
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
