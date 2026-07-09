//
//  MRZEntryViewCanKey.swift
//  NFCPassportReaderApp
//
//  Created by Andy Qua on 10/02/2021.
//  Copyright © 2021 Andy Qua. All rights reserved.
//

import SwiftUI


// This should be a nice simple inline DatePicker here
// BUT there are bugs when you select dates it changes the date format
// from DD MMM YYYY to DD/MM/YYYY!)
// Will update when/if this gets fixed!
struct MRZEntryViewCanKey : View {
    @EnvironmentObject var settings: SettingsStoreCAN
    
    // These will be removed once DatePicker inline works correctly
    @State private var editDOB = false
    @State private var editDOE = false
    @State private var editDateTitle : String = ""

    var body : some View {
        let passportNrBinding = Binding<String>(get: {
            settings.passportNumber
        }, set: {
            settings.passportNumber = $0.uppercased()
        })
        VStack {

            TextField("Can passport number", text: passportNrBinding)
                .textCase(.uppercase)
                .modifier(ClearButton(text: passportNrBinding))
                .textContentType(.name)
                .foregroundColor(Color.primary)
                .padding([.leading, .trailing])
                .ignoresSafeArea(.keyboard, edges: .all)

            Divider()

        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }
}


#if DEBUG
struct MRZEntryViewCanKey_Previews : PreviewProvider {
    
    static var previews: some View {
        let settings = SettingsStore()
        
        return
            Group {
                NavigationView {
                    MRZEntryViewCanKey()
                }
                .environmentObject(settings)
                .environment( \.colorScheme, .light)
        }
    }
}
#endif

