package org.wikipedia.nearby;

import org.json.JSONException;
import org.json.JSONObject;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Data object for the nearby results. Contains the list of nearby pages the API has returned to us.
 */
class NearbyResult implements Parcelable {
    private final JSONObject jsonObject;
    private final ArrayList<NearbyPage> list;

    /** empty result */
    public NearbyResult() {
        jsonObject = new JSONObject();
        list = new ArrayList<NearbyPage>();
    }

    /** non-empty result */
    NearbyResult(JSONObject jsonObject) throws JSONException {
        this.jsonObject = jsonObject;
        list = new ArrayList<NearbyPage>();
        final JSONObject pagesMap = jsonObject.optJSONObject("query").optJSONObject("pages");
        Iterator iterator = pagesMap.keys();

        while (iterator.hasNext()) {
            NearbyPage newPage = new NearbyPage(pagesMap.getJSONObject((String) iterator.next()));
            list.add(newPage);
        }
    }

    public JSONObject toJSON() {
        return jsonObject;
    }

    public ArrayList<NearbyPage> getList() {
        return list;
    }

    // Parcelable start

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(jsonObject.toString());
    }

    private NearbyResult(Parcel in) throws JSONException {
        this(new JSONObject(in.readString()));
    }

    public static final Parcelable.Creator<NearbyResult> CREATOR = new Parcelable.Creator<NearbyResult>() {
        public NearbyResult createFromParcel(Parcel in) {
            try {
                return new NearbyResult(in);
            } catch (JSONException e) {
                return new NearbyResult();
            }
        }

        public NearbyResult[] newArray(int size) {
            return new NearbyResult[size];
        }
    };

    // Parcelable end
}
